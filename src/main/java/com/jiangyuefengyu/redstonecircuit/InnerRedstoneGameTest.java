package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-world tests for inner-redstone power propagation.
 *
 * <p>These need a real level: whether two inner components couple depends on the surrounding
 * blocks being redstone conductors or transparent blocks, which cannot be modelled faithfully
 * outside a running game. The headless maths lives in {@code PowerSolverTest}.
 *
 * <p>They use the {@code redstonecircuit:empty} structure template (a 3x3x3 box with a stone floor
 * and a barrier shell) and place whatever else they need with {@code setBlock}.
 *
 * <p><b>Careful with coordinates:</b> {@code GameTestHelper#setBlock} takes a position
 * <em>relative to the structure</em>, while the inner-redstone store is keyed by absolute world
 * positions. Mixing the two silently produces a network that can never connect, so every position
 * used for store access goes through {@link #at}.
 *
 * <p>Run with {@code gradlew runGameTestServer}, or via {@code /test} in game.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class InnerRedstoneGameTest {

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    /** Places a block using the relative coordinates the test body reads. */
    private static void setBlock(GameTestHelper helper, int x, int y, int z, BlockState state) {
        helper.setBlock(new BlockPos(x, y, z), state);
    }

    /** Places inner dust at an absolute position and returns its slot so tests can inspect power. */
    private static Slot placeDust(GameTestHelper helper, BlockPos pos, int power) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);

        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        // Only a non-zero seed is a power input. A component that is merely a receiver must not be
        // marked as a source, otherwise it would never drain when its supply is removed.
        slot.fixedSource = power > 0;

        InnerRedstoneNode node = store.getOrCreate(pos);
        node.setSlot(slot);
        store.markDirty();
        return slot;
    }

    /** Runs propagation synchronously so the assertions do not have to wait for a tick. */
    private static void solve(GameTestHelper helper, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneNetwork.recompute(level, InnerRedstoneStore.get(level), pos);
    }

    /** Current power of the inner dust at an absolute position, or -1 when there is none. */
    private static int powerAt(GameTestHelper helper, BlockPos pos) {
        InnerRedstoneNode node = InnerRedstoneStore.get(helper.getLevel()).get(pos);
        return node == null || node.isEmpty() ? -1 : node.power();
    }

    // --------------------------------------------------------------- tests --

    /**
     * Coupling check used while developing the solver, kept as a plain assertion.
     *
     * <p>Worth keeping because wire coupling was the single hardest part to get right: if it ever
     * regresses, this fails with the exact positions and powers involved rather than a bare
     * "expected 14 but got 0".
     */
    @GameTest(template = "empty")
    public void verticalCouplingIsVisibleToTheStore(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 2, 1, Blocks.STONE.defaultBlockState());

        BlockPos lower = at(helper, 1, 1, 1);
        BlockPos upper = at(helper, 1, 2, 1);

        placeDust(helper, upper, 15);
        placeDust(helper, lower, 0);

        // The store must agree about where the two components are before anything is solved.
        helper.assertTrue(powerAt(helper, upper) == 15, "upper should hold 15 before solving");
        helper.assertTrue(powerAt(helper, lower) == 0, "lower should start unpowered");

        solve(helper, lower);

        helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, lower) == 14,
                "expected 14 for " + lower + " (upper " + upper + " holds "
                        + powerAt(helper, upper) + "), got " + powerAt(helper, lower)));
    }

    /**
     * The headline feature: dust inside a block couples to dust inside the block directly above,
     * even though both blocks are solid and no vanilla redstone wire is involved.
     */
    @GameTest(template = "empty")
    public void verticalNeighboursCouple(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 2, 1, Blocks.STONE.defaultBlockState());

        BlockPos lower = at(helper, 1, 1, 1);
        BlockPos upper = at(helper, 1, 2, 1);

        placeDust(helper, upper, 15);
        placeDust(helper, lower, 0);

        solve(helper, lower);

        helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, lower) == 14,
                "dust below should receive 15 - 1 = 14, but got " + powerAt(helper, lower)));
    }

    /** A vertical chain loses exactly one strength per step. */
    @GameTest(template = "empty")
    public void verticalChainDecaysByOne(GameTestHelper helper) {
        for (int y = 1; y <= 3; y++) {
            setBlock(helper, 1, y, 1, Blocks.STONE.defaultBlockState());
        }

        placeDust(helper, at(helper, 1, 3, 1), 15);
        placeDust(helper, at(helper, 1, 2, 1), 0);
        placeDust(helper, at(helper, 1, 1, 1), 0);

        solve(helper, at(helper, 1, 1, 1));

        helper.succeedWhen(() -> {
            int middle = powerAt(helper, at(helper, 1, 2, 1));
            int bottom = powerAt(helper, at(helper, 1, 1, 1));
            helper.assertTrue(middle == 14, "middle should be 14, got " + middle);
            helper.assertTrue(bottom == 13, "bottom should be 13, got " + bottom);
        });
    }

    /** Horizontal neighbours couple too. */
    @GameTest(template = "empty")
    public void horizontalNeighboursCouple(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());

        placeDust(helper, at(helper, 2, 1, 1), 15);
        BlockPos target = at(helper, 1, 1, 1);
        placeDust(helper, target, 0);

        solve(helper, target);

        helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, target) == 14,
                "horizontal neighbour should be 14, got " + powerAt(helper, target)));
    }

    /**
     * A lever pressed against a host block powers the redstone inside it.
     *
     * <p>This is the "outside to inside" half of the design. No bridging code is involved: the
     * inner network simply reads the same neighbour signals vanilla would.
     */
    @GameTest(template = "empty")
    public void externalLeverPowersInnerDust(GameTestHelper helper) {
        setBlock(helper, 2, 1, 2, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 2, Blocks.LEVER.defaultBlockState());

        // A lever on the floor pointing east, switched on.
        // Levers use HORIZONTAL_FACING plus FACE, not the six-way FACING property.
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.POWERED, true);
        setBlock(helper, 1, 1, 2, lever);

        BlockPos innerPos = at(helper, 2, 1, 2);
        placeDust(helper, innerPos, 0);
        solve(helper, innerPos);

        helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, innerPos) == 15,
                "a powered lever touching the host should push 15 into it, got " + powerAt(helper, innerPos)));
    }

    /** Power must fall back to zero once the source component is gone. */
    @GameTest(template = "empty")
    public void powerDropsWhenSourceRemoved(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 2, 1, Blocks.STONE.defaultBlockState());

        BlockPos lower = at(helper, 1, 1, 1);
        BlockPos upper = at(helper, 1, 2, 1);

        placeDust(helper, upper, 15);
        placeDust(helper, lower, 0);
        solve(helper, lower);

        InnerRedstoneStore store = InnerRedstoneStore.get(helper.getLevel());
        int before = powerAt(helper, lower);
        helper.assertTrue(before == 14, "precondition: lower should be powered, got " + before);

        // Drop the source, then re-solve the receiver.
        store.remove(upper);
        InnerRedstoneNetwork.markDirtyWithNeighbours(helper.getLevel(), upper);
        solve(helper, lower);

        helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, lower) == 0,
                "power must fall back to 0 once the source is gone, got " + powerAt(helper, lower)));
    }
}

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
 * <p>They use the {@code redstonecircuit:empty} structure template - a 5x3x5 box of pure air, so
 * every block a test needs (including the floor under a piece of vanilla wire) is placed by the test
 * itself with {@code setBlock}.
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
        // Only a non-zero seed is a supply (the same thing /rc place injects). A component that is
        // merely a receiver must not be marked as a source, otherwise it would never drain when its
        // supply is removed.
        if (power > 0) {
            slot.injectedPower = power;
        }

        InnerRedstoneNode node = store.getOrCreate(pos);
        node.setSlot(slot);
        store.markDirty();
        // The same scheduling the real placement path performs: the new component's own power has to
        // be derived (it may be fed by the world around it), and its neighbours may now be fed by it.
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        // ...and tell vanilla consumers to re-check, since no block state changed.
        InnerRedstoneInteraction.notifyNeighbours(level, pos);
        return slot;
    }

    /** Removes a component's injected supply, as if the lever feeding it had been taken away. */
    private static void clearSource(GameTestHelper helper, BlockPos pos) {
        InnerRedstoneNode node = InnerRedstoneStore.get(helper.getLevel()).get(pos);
        if (node != null && !node.isEmpty()) {
            node.slot().injectedPower = -1;
        }
    }

    /**
     * Runs propagation the way the server does.
     *
     * <p>Deliberately goes through the queue and {@link InnerRedstoneNetwork#settle} rather than
     * calling {@code recompute} directly, so the tests exercise the real path - including the
     * neighbour updates a solve sends back into the world.
     */
    private static void solve(GameTestHelper helper, BlockPos pos) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.settle(level);
    }

    /** Current power of the inner dust at an absolute position, or -1 when there is none. */
    private static int powerAt(GameTestHelper helper, BlockPos pos) {
        InnerRedstoneNode node = InnerRedstoneStore.get(helper.getLevel()).get(pos);
        return node == null || node.isEmpty() ? -1 : node.power();
    }

    /** Power of the vanilla redstone wire at a structure-relative position, or -1 when there is none. */
    private static int wirePower(GameTestHelper helper, int x, int y, int z) {
        BlockState state = helper.getBlockState(new BlockPos(x, y, z));
        return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(BlockStateProperties.POWER) : -1;
    }

    /** Whether the vanilla redstone lamp at a structure-relative position is lit. */
    private static boolean lampLit(GameTestHelper helper, int x, int y, int z) {
        return helper.getBlockState(new BlockPos(x, y, z)).getValue(BlockStateProperties.LIT);
    }

    /**
     * Runs {@code action} once the world has actually ticked.
     *
     * <p>Succeeding from the very first tick is unreliable here: a lamp or piston only re-evaluates
     * its neighbours when a block update reaches it, and that happens during the tick following the
     * change. Waiting one tick also exercises the real code path players will hit.
     */
    private static void afterTicks(GameTestHelper helper, int ticks, Runnable action) {
        if (ticks <= 0) {
            action.run();
            return;
        }
        helper.runAfterDelay(ticks, () -> afterTicks(helper, ticks - 1, action));
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
     * A lever pressed against a host block powers the redstone inside it, through the real
     * event path - no manual {@code solve} call.
     *
     * <p>This is the regression test for a bug where levers and torches appeared to do nothing: the
     * inner network only re-solved when a component was placed or removed by this mod, so an
     * external block change never triggered a recalculation.
     */
    @GameTest(template = "empty")
    public void externalLeverPowersInnerDust(GameTestHelper helper) {
        setBlock(helper, 2, 1, 2, Blocks.STONE.defaultBlockState());
        BlockPos host = at(helper, 2, 1, 2);
        placeDust(helper, host, 0);

        // A lever on the floor pointing east, switched on, touching the host block.
        setBlock(helper, 1, 1, 2, Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.POWERED, true));

        afterTicks(helper, 4, () -> helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, host) == 15,
                "a powered lever touching the host should push 15 into it, got " + powerAt(helper, host))));
    }

    /**
     * A vanilla redstone block next to a host powers the redstone inside it, and removing it drains
     * again - both through the real event path.
     *
     * <p>Uses a redstone block rather than a torch because it emits a full-strength signal in every
     * direction, so a failure here points at the event wiring rather than at torch orientation.
     */
    @GameTest(template = "empty")
    public void redstoneBlockPowersAndThenDrainsInnerDust(GameTestHelper helper) {
        setBlock(helper, 2, 1, 2, Blocks.STONE.defaultBlockState());
        BlockPos host = at(helper, 2, 1, 2);
        placeDust(helper, host, 0);

        setBlock(helper, 1, 1, 2, Blocks.REDSTONE_BLOCK.defaultBlockState());

        afterTicks(helper, 4, () -> {
            helper.assertTrue(powerAt(helper, host) == 15,
                    "a redstone block beside the host should push 15 in, got " + powerAt(helper, host));

            setBlock(helper, 1, 1, 2, Blocks.AIR.defaultBlockState());
            afterTicks(helper, 4, () -> helper.succeedWhen(() -> helper.assertTrue(
                    powerAt(helper, host) == 0,
                    "removing the redstone block must drain the inner redstone, got "
                            + powerAt(helper, host))));
        });
    }

    /**
     * Two adjacent hosts in a chain must hold their values and then drain together when the source
     * goes away.
     *
     * <p>Regression test for two separate reports: a chain of components decaying towards zero while
     * the source was still present, and power staying latched after the source was removed. Both had
     * the same cause - a component treating its neighbour's <em>settled value</em> as a supply, so a
     * pair fed off each other. The rule is now stated in terms of each component's own supply.
     */
    @GameTest(template = "empty")
    public void chainHoldsValuesThenDrainsWhenSourceRemoved(GameTestHelper helper) {
        setBlock(helper, 2, 1, 2, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 3, Blocks.STONE.defaultBlockState());

        BlockPos near = at(helper, 2, 1, 2);
        BlockPos far = at(helper, 2, 1, 3);
        placeDust(helper, near, 0);
        placeDust(helper, far, 0);

        // A lit torch against the near host.
        setBlock(helper, 1, 1, 2, Blocks.REDSTONE_TORCH.defaultBlockState());

        afterTicks(helper, 4, () -> {
            helper.assertTrue(powerAt(helper, near) == 15,
                    "the near component should be 15 from the torch, got " + powerAt(helper, near));
            helper.assertTrue(powerAt(helper, far) == 14,
                    "the far component should be 14 (one hop), got " + powerAt(helper, far));

            // Remove the source; BOTH must fall back to zero, with no decay carousel in between.
            setBlock(helper, 1, 1, 2, Blocks.AIR.defaultBlockState());
            afterTicks(helper, 6, () -> helper.succeedWhen(() -> {
                helper.assertTrue(powerAt(helper, near) == 0,
                        "the near component must drain to 0, got " + powerAt(helper, near));
                helper.assertTrue(powerAt(helper, far) == 0,
                        "the far component must drain to 0, got " + powerAt(helper, far));
            }));
        });
    }

    /**
     * A lit redstone torch standing beside a host powers the redstone inside it.
     *
     * <p>A standing torch emits 15 downwards ({@code getSignal(..., DOWN)}), which is exactly what
     * the host's neighbour query asks for. This is the case reported as "torches do nothing".
     */
    @GameTest(template = "empty")
    public void redstoneTorchPowersInnerDust(GameTestHelper helper) {
        setBlock(helper, 2, 1, 2, Blocks.STONE.defaultBlockState());
        BlockPos host = at(helper, 2, 1, 2);
        placeDust(helper, host, 0);

        // A standing redstone torch on the floor tile next to the host.
        setBlock(helper, 1, 1, 2, Blocks.REDSTONE_TORCH.defaultBlockState());

        afterTicks(helper, 4, () -> helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, host) == 15,
                "a lit redstone torch beside the host should push 15 in, got " + powerAt(helper, host))));
    }

    /**
     * Removing an external lever must drain the inner redstone again, through the real event path.
     *
     * <p>Regression test for power staying latched after the source was removed.
     */
    @GameTest(template = "empty")
    public void removingLeverDrainsInnerDust(GameTestHelper helper) {
        setBlock(helper, 2, 1, 2, Blocks.STONE.defaultBlockState());
        BlockPos host = at(helper, 2, 1, 2);
        placeDust(helper, host, 0);

        BlockState leverOn = Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST)
                .setValue(BlockStateProperties.POWERED, true);
        setBlock(helper, 1, 1, 2, leverOn);

        afterTicks(helper, 4, () -> {
            helper.assertTrue(powerAt(helper, host) == 15,
                    "precondition: the lever should have powered the dust, got " + powerAt(helper, host));

            // Take the lever away and give the network time to notice.
            setBlock(helper, 1, 1, 2, Blocks.AIR.defaultBlockState());
            afterTicks(helper, 4, () -> helper.succeedWhen(() -> helper.assertTrue(
                    powerAt(helper, host) == 0,
                    "removing the lever must drain the inner redstone, got " + powerAt(helper, host))));
        });
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

    // ---------------------------------------------------- inner -> outer ------

    /**
     * The headline of this stage: an inner component lights a vanilla redstone lamp beside its host
     * block. This is what {@code BlockStateSignalMixin} exists for - no other NeoForge hook lets an
     * untouched vanilla block emit a signal.
     *
     * <p>The signal itself is asserted first, because if that fails the cause is the mixin, whereas
     * if only the lamp stays dark the cause is update propagation and needs a tick.
     */
    @GameTest(template = "empty")
    public void innerRedstoneLightsAdjacentLamp(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 15);

        ServerLevel level = helper.getLevel();
        BlockState hostState = level.getBlockState(host);
        int emitted = hostState.getSignal(level, host, Direction.EAST);
        int direct = hostState.getDirectSignal(level, host, Direction.EAST);

        if (emitted != 15 || direct != 15) {
            helper.fail("the host block should emit 15 in both signal queries,"
                    + " but getSignal=" + emitted + " getDirectSignal=" + direct);
            return;
        }

        afterTicks(helper, 2, () -> helper.succeedWhen(() -> helper.assertTrue(
                helper.getBlockState(new BlockPos(2, 1, 1)).getValue(BlockStateProperties.LIT),
                "the lamp beside a powered host block should be lit")));
    }

    /** Control: a host with no component must leave the neighbouring lamp alone. */
    @GameTest(template = "empty")
    public void emptyHostDoesNotLightLamp(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        placeDust(helper, at(helper, 1, 1, 1), 0);

        helper.succeedWhen(() -> helper.assertTrue(
                !helper.getBlockState(new BlockPos(2, 1, 1)).getValue(BlockStateProperties.LIT),
                "an unpowered component must not light the lamp"));
    }

    /** A component in the block above must power an adjacent lamp the same way. */
    @GameTest(template = "empty")
    public void innerRedstoneAboveLightsLamp(GameTestHelper helper) {
        setBlock(helper, 1, 2, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 2, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        BlockPos host = at(helper, 1, 2, 1);
        placeDust(helper, host, 15);

        ServerLevel level = helper.getLevel();
        BlockState hostState = level.getBlockState(host);
        StringBuilder diag = new StringBuilder("host=" + host
                + " hostBlock=" + hostState.getBlock()
                + " storeSignal=" + InnerRedstoneStore.get(level).getSignal(host)
                + " storedNode=" + (InnerRedstoneStore.get(level).get(host) != null)
                + " storeEmpty=" + InnerRedstoneStore.get(level).isEmpty()
                + " suppressed=" + InnerRedstoneNetwork.isSignalSuppressed(level)
                + " signals:");
        for (Direction d : Direction.values()) {
            diag.append(' ').append(d).append('=').append(hostState.getSignal(level, host, d));
        }
        diag.append("  lampBlock=").append(level.getBlockState(at(helper, 2, 2, 1)).getBlock());
        diag.append("  lampLit=")
            .append(level.getBlockState(at(helper, 2, 2, 1)).getValue(BlockStateProperties.LIT));

        afterTicks(helper, 2, () -> helper.succeedWhen(() -> helper.assertTrue(
                helper.getBlockState(new BlockPos(2, 2, 1)).getValue(BlockStateProperties.LIT),
                "the lamp beside the upper host block should be lit. " + diag)));
    }

    /**
     * A host block lights a lamp on each of the four horizontal sides.
     *
     * <p>A lamp <em>above</em> the host is deliberately not included: it queries its neighbour below
     * with direction {@code DOWN}, which hosts do not signal (mirroring vanilla wire). That asymmetry
     * is verified by {@link #innerRedstoneSignalsEveryDirectionExceptDown}.
     */
    @GameTest(template = "empty")
    public void innerRedstoneLightsLampsOnAllSides(GameTestHelper helper) {
        setBlock(helper, 2, 1, 2, Blocks.STONE.defaultBlockState());

        setBlock(helper, 3, 1, 2, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 1, 1, 2, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 2, 1, 3, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        placeDust(helper, at(helper, 2, 1, 2), 15);

        BlockPos[] lamps = {
                new BlockPos(3, 1, 2),
                new BlockPos(1, 1, 2),
                new BlockPos(2, 1, 3),
                new BlockPos(2, 1, 1)
        };

        afterTicks(helper, 2, () -> helper.succeedWhen(() -> {
            for (BlockPos lamp : lamps) {
                helper.assertTrue(
                        helper.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                        "lamp at " + lamp + " should be lit");
            }
        }));
    }

    /**
     * A host block powers every side except the one below it, mirroring vanilla wire.
     */
    @GameTest(template = "empty")
    public void innerRedstoneSignalsEveryDirectionExceptDown(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 15);

        ServerLevel level = helper.getLevel();
        BlockState hostState = level.getBlockState(host);

        for (Direction direction : Direction.values()) {
            int signal = hostState.getSignal(level, host, direction);
            if (direction == Direction.DOWN) {
                helper.assertTrue(signal == 0,
                        "a host block must not signal downwards, got " + signal);
            } else {
                helper.assertTrue(signal == 15,
                        "direction " + direction + " should emit 15, got " + signal);
            }
        }
        helper.succeed();
    }

    // ------------------------------------- draining back into the world ------

    /**
     * The reported bug: the inner redstone drained correctly, but the redstone lamp beside the host
     * stayed lit, and only went out when an unrelated block next to it was broken.
     *
     * <p>Nothing was wrong with the solver - the stored power really did fall to 0. The problem was
     * that a stored value is not a block state: dropping it changed no world data, so nothing ever
     * notified the lamp, and it kept whatever state it happened to hold. A solve now ends by sending
     * a vanilla neighbour update for every host whose power changed.
     */
    @GameTest(template = "empty")
    public void lampGoesOutWhenInnerPowerDrains(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 15);

        afterTicks(helper, 2, () -> {
            helper.assertTrue(lampLit(helper, 2, 1, 1),
                    "precondition: the lamp should be lit by the powered host");

            clearSource(helper, host);
            solve(helper, host);

            // The lamp schedules its switch-off for a few ticks later, hence the generous delay.
            afterTicks(helper, 6, () -> helper.succeedWhen(() -> helper.assertTrue(
                    !lampLit(helper, 2, 1, 1),
                    "the lamp must go out once the inner redstone drains, with no block broken")));
        });
    }

    /**
     * The same drain, seen through vanilla redstone wire laid against the host block - the check
     * that was used to report the bug ("I put redstone next to the block and it was not activated").
     *
     * <p>This is also the regression test for the loop the wire creates: the wire is powered by the
     * host and the host reads the wire, so treating the wire as a full-strength supply let the two
     * hold each other up at 15 forever. The wire is charged one hop instead, exactly as vanilla
     * charges a hop between two wires, which leaves the pair no way to stay powered on their own.
     */
    @GameTest(template = "empty")
    public void vanillaWireFollowsInnerPower(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 0, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_WIRE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 15);

        afterTicks(helper, 2, () -> {
            helper.assertTrue(wirePower(helper, 2, 1, 1) == 15,
                    "precondition: the wire beside the powered host should carry 15, got "
                            + wirePower(helper, 2, 1, 1));

            clearSource(helper, host);
            solve(helper, host);

            afterTicks(helper, 2, () -> helper.succeedWhen(() -> helper.assertTrue(
                    wirePower(helper, 2, 1, 1) == 0,
                    "the wire must go dark together with the inner redstone, got "
                            + wirePower(helper, 2, 1, 1))));
        });
    }

    /**
     * A wire laid against a host block feeds the redstone inside it, one hop down - the price of a
     * wire always costing a hop, and what makes {@link #vanillaWireFollowsInnerPower} possible.
     */
    @GameTest(template = "empty")
    public void vanillaWireFeedsInnerRedstone(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 0, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_WIRE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 0);

        // The source goes in last, so the wire - and through it this host - is notified of it.
        setBlock(helper, 3, 1, 1, Blocks.REDSTONE_BLOCK.defaultBlockState());

        afterTicks(helper, 4, () -> helper.succeedWhen(() -> {
            helper.assertTrue(wirePower(helper, 2, 1, 1) == 15,
                    "the redstone block should hold the wire at 15, got " + wirePower(helper, 2, 1, 1));
            helper.assertTrue(powerAt(helper, host) == 14,
                    "a neighbouring wire is charged one hop, so the host should hold 14, got "
                            + powerAt(helper, host));
        }));
    }
}

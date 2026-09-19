package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.InnerSwitches;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks;

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

    /** Places an inner component of any type and returns its slot so tests can inspect power. */
    private static Slot placeComponent(GameTestHelper helper, BlockPos pos, ComponentType type,
                                       int power, Direction facing) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);

        Slot slot = new Slot(type);
        slot.power = power;
        // FACING is the component's input side: the side it reads, and for a torch the side it hangs on.
        slot.facing = facing;

        InnerRedstoneNode node = store.getOrCreate(pos);
        node.setSlot(slot);
        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneInteraction.notifyNeighbours(level, pos);
        return slot;
    }

    /** Resolves everything queued for this level the way one server tick would. */
    private static void tick(GameTestHelper helper) {
        InnerRedstoneNetwork.tick(helper.getLevel());
    }

    /** The slot stored at an absolute position, for asserting on a component's own state. */
    private static Slot slotAt(GameTestHelper helper, BlockPos pos) {
        InnerRedstoneNode node = InnerRedstoneStore.get(helper.getLevel()).get(pos);
        return node == null || node.isEmpty() ? null : node.slot();
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
        helper.runAfterDelay(ticks, action);
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

        if (emitted != 15) {
            helper.fail("the host block should emit 15 towards the lamp, but getSignal=" + emitted);
            return;
        }
        if (direct != 0) {
            helper.fail("inner dust is a weak source and must not charge the block it touches,"
                    + " but getDirectSignal=" + direct);
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
     * <p>A lamp above or below the host is not included here either - those are covered by
     * {@link #hostLightsTheLampAboveIt}, since a host emits in every direction
     * ({@link #innerRedstoneSignalsEveryDirection}).
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
     * A host block powers every side, including the one above and the one below it.
     *
     * <p>Vanilla wire does not power the block above it, but that rule belongs to the wire block, not
     * to the block a component is embedded in: the host is acting as a signal source, so all six
     * sides are driven.
     */
    @GameTest(template = "empty")
    public void innerRedstoneSignalsEveryDirection(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 15);

        ServerLevel level = helper.getLevel();
        BlockState hostState = level.getBlockState(host);

        for (Direction direction : Direction.values()) {
            int signal = hostState.getSignal(level, host, direction);
            helper.assertTrue(signal == 15,
                    "direction " + direction + " should emit 15, got " + signal);
        }
        helper.succeed();
    }

    /**
     * The direction that used to be missing: a block above a host is driven like any other side.
     *
     * <p>Reported as "everything works except upwards". The host used to keep vanilla wire's quirk of
     * not powering the block above it, which is fine for a wire lying on the ground but wrong for a
     * block that is acting as a signal source - a lamp on top of it stayed dark while the same lamp
     * below it lit.
     */
    @GameTest(template = "empty")
    public void hostLightsTheLampAboveIt(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 2, 1, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 1, 0, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        placeDust(helper, at(helper, 1, 1, 1), 15);

        afterTicks(helper, 2, () -> helper.succeedWhen(() -> {
            helper.assertTrue(lampLit(helper, 1, 2, 1), "the lamp above the host should be lit");
            helper.assertTrue(lampLit(helper, 1, 0, 1), "and so should the one below it");
        }));
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

    // ------------------------------------------- directional sources ---------

    /**
     * A repeater pointing into a host block powers the redstone inside it.
     *
     * <p>Regression test for a real bug: the neighbour query was made with the direction reversed.
     * Sources that emit in five or six directions - levers, torches, redstone blocks, wire - hid it,
     * because the wrong side still answers 15. A repeater answers only for the side it points at, so
     * with the reversed query it reported nothing at all.
     */
    @GameTest(template = "empty")
    public void repeaterOutputPowersInnerRedstone(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 0, 1, Blocks.STONE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 0);

        // FACING points at a repeater's *input*, so this one reads east and outputs west, into the
        // host. The redstone block behind it switches it on.
        setBlock(helper, 2, 1, 1, Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        setBlock(helper, 3, 1, 1, Blocks.REDSTONE_BLOCK.defaultBlockState());

        afterTicks(helper, 8, () -> helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, host) == 15,
                "a repeater pointing into the host should push 15, got " + powerAt(helper, host))));
    }

    /**
     * The reported case: an observer pressed against a host block, emitting into it, powers the
     * redstone inside - no vanilla wire needed in between.
     *
     * <p>{@code ObserverBlock#getSignal} answers 15 only for the side it emits from, so the reversed
     * query made it report nothing while a wire laid against the host worked (a wire emits in five
     * directions). That is exactly the "the observer against the block does nothing, it needs a piece
     * of redstone in between" report.
     */
    @GameTest(template = "empty")
    public void observerEmittingIntoHostPowersInnerRedstone(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 0);

        // FACING points at what an observer watches, so this one watches east and emits west, into
        // the host: the observer against the block, output facing it.
        setBlock(helper, 2, 1, 1, Blocks.OBSERVER.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));

        // Arm the check *before* triggering the pulse: an observer only stays powered for two ticks,
        // so a delayed assertion could miss it entirely.
        helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, host) > 0,
                "an observer emitting into the host must power the inner redstone, got "
                        + powerAt(helper, host)));

        // Changing the block it watches is what makes it pulse.
        setBlock(helper, 3, 1, 1, Blocks.STONE.defaultBlockState());
    }

    /**
     * A block that a repeater strongly powers feeds the redstone inside the host beside it.
     *
     * <p>Vanilla's {@code getBestNeighborSignal} looks at more than what a neighbour emits: for a
     * full block it also adds the strong power that block <em>receives</em>. The solver now asks
     * through {@code Level#getSignal}, exactly like vanilla, so "repeater into a block, redstone off
     * that block" works with inner redstone too.
     */
    @GameTest(template = "empty")
    public void stronglyPoweredBlockFeedsInnerRedstone(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 3, 0, 1, Blocks.STONE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 0);

        // The repeater reads east and outputs west, into the plain stone block at 2,1,1.
        setBlock(helper, 3, 1, 1, Blocks.REPEATER.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST));
        setBlock(helper, 4, 1, 1, Blocks.REDSTONE_BLOCK.defaultBlockState());

        afterTicks(helper, 8, () -> helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, host) == 15,
                "a block strongly powered by the repeater should feed the host, got "
                        + powerAt(helper, host))));
    }

    // ------------------------------------------------- components in a block --

    /**
     * A lever stored inside a block powers the block next door.
     *
     * <p>The first component test, and the one that shows what a source is worth: a lever is not wire,
     * so the dust it feeds holds the full 15 rather than 14 - exactly as a lever does against a piece
     * of vanilla wire.
     */
    @GameTest(template = "empty")
    public void leverInsideHostPowersDust(GameTestHelper helper) {
        setBlock(helper, 0, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos lever = at(helper, 0, 1, 1);
        BlockPos dust = at(helper, 1, 1, 1);
        placeComponent(helper, lever, ComponentType.LEVER, 0, Direction.NORTH);
        placeDust(helper, dust, 0);

        tick(helper);
        helper.assertTrue(powerAt(helper, dust) == 0,
                "precondition: a lever that is off powers nothing");

        InnerSwitches.toggle(helper.getLevel(), lever);
        tick(helper);
        helper.assertTrue(powerAt(helper, dust) == 15,
                "a lever inside the block should light the dust beside it at full strength, got "
                        + powerAt(helper, dust));

        InnerSwitches.toggle(helper.getLevel(), lever);
        tick(helper);
        helper.succeedWhen(() -> helper.assertTrue(powerAt(helper, dust) == 0,
                "and switching it off must drain the dust again, got " + powerAt(helper, dust)));
    }

    /**
     * A torch stored inside a block inverts its input, and takes its two ticks to do it.
     *
     * <p>This is a torch's whole purpose: lit on its own, out once the block it hangs on is powered.
     * The torch here hangs on its west side, so the lever drives it and the dust to the east is what
     * it lights.
     */
    @GameTest(template = "empty")
    public void torchInsideHostInvertsItsInput(GameTestHelper helper) {
        setBlock(helper, 0, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos lever = at(helper, 0, 1, 1);
        BlockPos torch = at(helper, 1, 1, 1);
        BlockPos dust = at(helper, 2, 1, 1);
        placeComponent(helper, lever, ComponentType.LEVER, 0, Direction.NORTH);
        placeComponent(helper, torch, ComponentType.TORCH, 15, Direction.WEST);
        placeDust(helper, dust, 0);

        afterTicks(helper, 4, () -> {
            helper.assertTrue(powerAt(helper, torch) == 15,
                    "precondition: nothing on its attachment side, so the torch is lit");
            helper.assertTrue(powerAt(helper, dust) == 15,
                    "precondition: a torch is a source, so the dust holds 15 and not 14");

            InnerSwitches.toggle(helper.getLevel(), lever);

            // A driven component never reacts in the tick it is told to: vanilla gives a torch two.
            afterTicks(helper, 1, () -> helper.assertTrue(powerAt(helper, torch) == 15,
                    "a torch must not follow its input within the same tick, got "
                            + powerAt(helper, torch)));

            afterTicks(helper, 6, () -> helper.succeedWhen(() -> {
                helper.assertTrue(powerAt(helper, torch) == 0,
                        "the lever on its attachment side puts the torch out, got "
                                + powerAt(helper, torch));
                helper.assertTrue(powerAt(helper, dust) == 0,
                        "and the dust goes dark with it, got " + powerAt(helper, dust));
            }));
        });
    }

    /**
     * A repeater stored inside a block amplifies a weak signal, after its setting in ticks.
     *
     * <p>An input of 13 comes out at 15, and delay 3 holds the old output for six ticks - the two
     * things a repeater exists for.
     */
    @GameTest(template = "empty")
    public void repeaterAmplifiesAndDelays(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) {
            setBlock(helper, x, 1, 1, Blocks.STONE.defaultBlockState());
        }

        BlockPos lever = at(helper, 0, 1, 1);
        BlockPos wire = at(helper, 2, 1, 1);
        BlockPos repeater = at(helper, 3, 1, 1);
        BlockPos output = at(helper, 4, 1, 1);
        placeComponent(helper, lever, ComponentType.LEVER, 0, Direction.NORTH);
        placeDust(helper, at(helper, 1, 1, 1), 0);
        placeDust(helper, wire, 0);
        Slot repeaterSlot = placeComponent(helper, repeater, ComponentType.REPEATER, 0, Direction.WEST);
        repeaterSlot.delay = 3;
        placeDust(helper, output, 0);

        tick(helper);
        InnerSwitches.toggle(helper.getLevel(), lever);
        tick(helper);
        helper.assertTrue(powerAt(helper, wire) == 14,
                "precondition: two wire hops from the lever, 15 -> 15 -> 14, got "
                        + powerAt(helper, wire));

        // Delay 3 is six ticks, so at four ticks the repeater must still be dark.
        afterTicks(helper, 4, () -> helper.assertTrue(powerAt(helper, repeater) == 0,
                "delay 3 must hold the output six ticks, but it already moved: "
                        + powerAt(helper, repeater)));

        afterTicks(helper, 12, () -> helper.succeedWhen(() -> {
            helper.assertTrue(powerAt(helper, repeater) == 15,
                    "a repeater puts any input out at full strength, got " + powerAt(helper, repeater));
            helper.assertTrue(powerAt(helper, output) == 15,
                    "and drives the wire on its output side with it, got " + powerAt(helper, output));
        }));
    }

    /**
     * A comparator stored inside a block compares its back against its sides, and subtracts when asked.
     *
     * <p>Both inputs are levers, so compare sees 15 against 15 and passes 15 through, while subtract
     * sees the same numbers and puts out nothing.
     */
    @GameTest(template = "empty")
    public void comparatorComparesThenSubtracts(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 3, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 2, Blocks.STONE.defaultBlockState());

        BlockPos back = at(helper, 1, 1, 1);
        BlockPos comparator = at(helper, 2, 1, 1);
        BlockPos side = at(helper, 2, 1, 2);
        BlockPos output = at(helper, 3, 1, 1);
        placeComponent(helper, back, ComponentType.LEVER, 15, Direction.NORTH);
        Slot comparatorSlot =
                placeComponent(helper, comparator, ComponentType.COMPARATOR, 0, Direction.WEST);
        placeComponent(helper, side, ComponentType.LEVER, 15, Direction.NORTH);
        placeDust(helper, output, 0);

        afterTicks(helper, 4, () -> {
            helper.assertTrue(powerAt(helper, output) == 15,
                    "compare mode passes the back signal through when the sides match it, got "
                            + powerAt(helper, output));

            comparatorSlot.mode = ComparatorMode.SUBTRACT;
            InnerRedstoneNetwork.markDirtyWithNeighbours(helper.getLevel(), comparator);

            afterTicks(helper, 6, () -> helper.succeedWhen(() -> helper.assertTrue(
                    powerAt(helper, output) == 0,
                    "subtract takes the side input off the back, so 15 - 15 is nothing, got "
                            + powerAt(helper, output))));
        });
    }

    /** A button stored inside a block springs back on its own, like vanilla's. */
    @GameTest(template = "empty")
    public void buttonInsideHostReleasesItself(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos button = at(helper, 1, 1, 1);
        BlockPos dust = at(helper, 2, 1, 1);
        placeComponent(helper, button, ComponentType.BUTTON, 0, Direction.NORTH);
        placeDust(helper, dust, 0);

        tick(helper);
        InnerSwitches.toggle(helper.getLevel(), button);
        tick(helper);
        helper.assertTrue(powerAt(helper, dust) == 15,
                "a pressed button powers the dust beside it, got " + powerAt(helper, dust));

        // One second later it lets go by itself, with no second click.
        afterTicks(helper, 40, () -> helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, dust) == 0,
                "a button must release itself, but the dust is still lit: " + powerAt(helper, dust))));
    }

    /**
     * A repeater switches off again when its input stops - even with a solid block on its input side.
     *
     * <p>Regression test for a reported bug: the repeater stayed on forever. The cause was not the
     * delay but the query that decides the switch-off. A neighbouring solid block reports the strong
     * power it receives, a host block is a signal source, and the query that ran at the scheduled tick
     * was the one place that did <em>not</em> suppress our own output - so the repeater's input side
     * read its own 15 back through the stone next door, its input never dropped, and the pending
     * switch-off was discarded as "computes to the same value".
     *
     * <p>The layout matters: the input side has to be a solid host block for the strong-power path to
     * exist. The earlier repeater test only ever switched the input on, where reading your own output
     * back happens to give the same answer the input already had.
     */
    @GameTest(template = "empty")
    public void repeaterTurnsOffWhenItsInputStops(GameTestHelper helper) {
        setBlock(helper, 0, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos lever = at(helper, 0, 1, 1);
        BlockPos repeater = at(helper, 1, 1, 1);
        BlockPos output = at(helper, 2, 1, 1);
        placeComponent(helper, lever, ComponentType.LEVER, 0, Direction.NORTH);
        placeComponent(helper, repeater, ComponentType.REPEATER, 0, Direction.WEST);
        placeDust(helper, output, 0);

        tick(helper);
        InnerSwitches.toggle(helper.getLevel(), lever);
        tick(helper);

        afterTicks(helper, 6, () -> {
            helper.assertTrue(powerAt(helper, repeater) == 15,
                    "precondition: the repeater followed its input up, got " + powerAt(helper, repeater));
            helper.assertTrue(powerAt(helper, output) == 15,
                    "precondition: and drove the wire beside it, got " + powerAt(helper, output));

            InnerSwitches.toggle(helper.getLevel(), lever);

            afterTicks(helper, 8, () -> helper.succeedWhen(() -> {
                helper.assertTrue(powerAt(helper, repeater) == 0,
                        "a repeater must switch off once its input stops, got "
                                + powerAt(helper, repeater));
                helper.assertTrue(powerAt(helper, output) == 0,
                        "and the wire on its output side must go dark with it, got "
                                + powerAt(helper, output));
            }));
        });
    }

    /**
     * A repeater inside a block drives only the side it points at, exactly like one on the ground.
     *
     * <p>A host block is not a redstone block: it emits what its component emits, and where. This is
     * what stops a repeater's output from leaking sideways into whatever vanilla wiring happens to run
     * alongside the block.
     */
    @GameTest(template = "empty")
    public void repeaterOnlySignalsTheWayItFaces(GameTestHelper helper) {
        // lever host at x=1, repeater host at x=2 with its input facing west, so its output is east.
        for (int x = 1; x <= 4; x++) {
            setBlock(helper, x, 1, 1, Blocks.STONE.defaultBlockState());
            // Shelves first: a wire needs something to sit on when it is placed.
            setBlock(helper, x, 0, 1, Blocks.STONE.defaultBlockState());
            setBlock(helper, x, 0, 2, Blocks.STONE.defaultBlockState());
        }
        // One lamp in front of the repeater, one beside it.
        setBlock(helper, 3, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 2, 1, 2, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 2, 0, 2, Blocks.STONE.defaultBlockState());

        placeComponent(helper, at(helper, 1, 1, 1), ComponentType.LEVER, 15, Direction.NORTH);
        placeComponent(helper, at(helper, 2, 1, 1), ComponentType.REPEATER, 0, Direction.WEST);

        afterTicks(helper, 6, () -> helper.succeedWhen(() -> {
            helper.assertTrue(lampLit(helper, 3, 1, 1),
                    "the lamp in front of the repeater should be lit");
            helper.assertTrue(!lampLit(helper, 2, 1, 2),
                    "the lamp beside the repeater must stay dark: a repeater points one way, and the"
                            + " lever's weak power must not be carried through the repeater's block");
        }));
    }

    /** The wrench's "disconnect" override really cuts a wire in the world, not only in the data. */
    @GameTest(template = "empty")
    public void forcedOffCutsAWire(GameTestHelper helper) {
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos source = at(helper, 1, 1, 1);
        BlockPos receiver = at(helper, 2, 1, 1);
        Slot sourceSlot = placeDust(helper, source, 15);
        placeDust(helper, receiver, 0);

        solve(helper, receiver);
        helper.assertTrue(powerAt(helper, receiver) == 14,
                "precondition: the two wires couple normally, got " + powerAt(helper, receiver));

        sourceSlot.setConnection(Direction.EAST, ConnectionState.OFF);
        solve(helper, receiver);

        helper.succeedWhen(() -> helper.assertTrue(powerAt(helper, receiver) == 0,
                "a disconnected side must carry nothing, got " + powerAt(helper, receiver)));
    }

    // ------------------------------------------------- superconducting dust ------

    /**
     * 超导红石粉 in a real level: a run of it carries fifteen all the way, where ordinary dust fades.
     *
     * <p>Both runs are fed the same way - a lever, then one ordinary wire, then the material under test -
     * so the only difference between them is the material itself.
     */
    @GameTest(template = "empty")
    public void superconductingDustCarriesSignalWithoutLoss(GameTestHelper helper) {
        layOutFedRun(helper, ComponentType.SUPERCONDUCTOR);

        solve(helper, at(helper, 4, 1, 1));
        for (int x = 2; x <= 4; x++) {
            int power = powerAt(helper, at(helper, x, 1, 1));
            helper.assertTrue(power == 15,
                    "superconducting dust at x=" + x + " must hold the full 15, got " + power);
        }

        helper.succeed();
    }

    /** The control for the test above: the same run in ordinary dust fades a step per block. */
    @GameTest(template = "empty")
    public void ordinaryDustStillFadesOverDistance(GameTestHelper helper) {
        layOutFedRun(helper, ComponentType.DUST);

        solve(helper, at(helper, 4, 1, 1));
        for (int x = 2; x <= 4; x++) {
            int expected = 15 - (x - 1);
            int power = powerAt(helper, at(helper, x, 1, 1));
            helper.assertTrue(power == expected,
                    "ordinary dust at x=" + x + " should have faded to " + expected + ", got " + power);
        }

        helper.succeed();
    }

    /**
     * Lever host at (0,1,1), an ordinary wire at (1,1,1) to carry its fifteen, then {@code material} for
     * the three blocks after it.
     */
    private static void layOutFedRun(GameTestHelper helper, ComponentType material) {
        for (int x = 0; x <= 4; x++) {
            setBlock(helper, x, 1, 1, Blocks.STONE.defaultBlockState());
        }
        placeComponent(helper, at(helper, 0, 1, 1), ComponentType.LEVER, 15, Direction.NORTH);
        placeDust(helper, at(helper, 1, 1, 1), 0);
        for (int x = 2; x <= 4; x++) {
            placeComponent(helper, at(helper, x, 1, 1), material, 0, Direction.NORTH);
        }
    }

    /**
     * The two materials mix: entering the superconductor costs nothing, leaving it costs the ordinary
     * dust's own hop.
     */
    @GameTest(template = "empty")
    public void superconductingDustMixesWithOrdinaryDust(GameTestHelper helper) {
        for (int x = 0; x <= 3; x++) {
            setBlock(helper, x, 1, 1, Blocks.STONE.defaultBlockState());
        }

        // lever(15) -> dust -> superconductor -> dust
        placeComponent(helper, at(helper, 0, 1, 1), ComponentType.LEVER, 15, Direction.NORTH);
        placeDust(helper, at(helper, 1, 1, 1), 0);
        placeComponent(helper, at(helper, 2, 1, 1), ComponentType.SUPERCONDUCTOR, 0, Direction.NORTH);
        placeDust(helper, at(helper, 3, 1, 1), 0);

        solve(helper, at(helper, 3, 1, 1));
        helper.assertTrue(powerAt(helper, at(helper, 1, 1, 1)) == 15,
                "a lever lights the first wire at full strength, got "
                        + powerAt(helper, at(helper, 1, 1, 1)));
        helper.assertTrue(powerAt(helper, at(helper, 2, 1, 1)) == 15,
                "ordinary dust feeding a superconductor charges nothing for the hop, got "
                        + powerAt(helper, at(helper, 2, 1, 1)));
        helper.assertTrue(powerAt(helper, at(helper, 3, 1, 1)) == 14,
                "and ordinary dust on the far side fades by one, as it always does, got "
                        + powerAt(helper, at(helper, 3, 1, 1)));

        helper.succeed();
    }

    // ---------------------------------------------------------------- wrench --

    /**
     * A bare hand works a repeater's delay and a comparator's mode, as it does on the ground.
     *
     * <p>Same gesture, same cycle: right-clicking a repeater steps 1-2-3-4 and back to 1, and
     * right-clicking a comparator swaps compare and subtract. The block it is buried in has no visible
     * state of its own, so this is checked through the stored data - which is also what the solver and
     * the drawing read.
     */
    @GameTest(template = "empty")
    public void bareHandAdjustsDiodeSettings(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos repeater = at(helper, 1, 1, 1);
        BlockPos comparator = at(helper, 2, 1, 1);
        Slot repeaterSlot = placeComponent(helper, repeater, ComponentType.REPEATER, 0, Direction.WEST);
        Slot comparatorSlot =
                placeComponent(helper, comparator, ComponentType.COMPARATOR, 0, Direction.WEST);
        helper.assertTrue(repeaterSlot.delay == 1, "a fresh repeater is one redstone tick");

        for (int expected = 2; expected <= 4; expected++) {
            helper.assertTrue(InnerSwitches.toggle(helper.getLevel(), repeater)
                            == InnerSwitches.Result.ADJUSTED,
                    "working a repeater is an adjustment");
            helper.assertTrue(repeaterSlot.delay == expected,
                    "the delay must step up to " + expected + ", got " + repeaterSlot.delay);
        }
        InnerSwitches.toggle(helper.getLevel(), repeater);
        helper.assertTrue(repeaterSlot.delay == 1,
                "and wrap back to one, as vanilla does, got " + repeaterSlot.delay);

        helper.assertTrue(comparatorSlot.mode == ComparatorMode.COMPARE,
                "a fresh comparator compares");
        InnerSwitches.toggle(helper.getLevel(), comparator);
        helper.assertTrue(comparatorSlot.mode == ComparatorMode.SUBTRACT,
                "one click switches it to subtract, got " + comparatorSlot.mode);
        InnerSwitches.toggle(helper.getLevel(), comparator);
        helper.assertTrue(comparatorSlot.mode == ComparatorMode.COMPARE,
                "and the next one switches it back, got " + comparatorSlot.mode);

        // A wire has no setting, so the click must not be swallowed - vanilla keeps it.
        setBlock(helper, 3, 1, 1, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 1), 0);
        helper.assertTrue(InnerSwitches.toggle(helper.getLevel(), at(helper, 3, 1, 1))
                        == InnerSwitches.Result.NONE,
                "a bare hand on a wire is not our click");
        helper.succeed();
    }

    /**
     * The headline of the wrench: a locked line is the <em>only</em> line, and there can be several.
     *
     * <p>Two wires that both couple to a source on their own. Locking one of them must leave it working
     * and cut the other one dead, which is what "除了这条线，其他线都被隔断" asks for - and it has to be
     * true in the world, not just in the stored overrides. Locking the second one afterwards must take a
     * single wrench use, because the cut that killed it was implied by the first lock rather than
     * chosen by the player.
     */
    @GameTest(template = "empty")
    public void wrenchLockCutsEveryOtherLine(GameTestHelper helper) {
        // Source host at (1,1,1) with wires east of it and south of it.
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 2, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 0, 2, Blocks.STONE.defaultBlockState());

        BlockPos source = at(helper, 1, 1, 1);
        BlockPos east = at(helper, 2, 1, 1);
        BlockPos south = at(helper, 1, 1, 2);
        Slot sourceSlot = placeDust(helper, source, 15);
        placeDust(helper, east, 0);
        placeDust(helper, south, 0);

        solve(helper, east);
        solve(helper, south);
        helper.assertTrue(powerAt(helper, east) == 14 && powerAt(helper, south) == 14,
                "precondition: both wires couple to the source on their own, got "
                        + powerAt(helper, east) + " and " + powerAt(helper, south));

        helper.assertTrue(WrenchLinks.link(helper.getLevel(), source, east)
                        == WrenchLinks.Result.LINKED_ON,
                "the first use locks the line");
        solve(helper, east);
        solve(helper, south);

        helper.assertTrue(powerAt(helper, east) == 14,
                "the locked line still carries the signal, got " + powerAt(helper, east));
        helper.assertTrue(powerAt(helper, south) == 0,
                "and every other line is cut, got " + powerAt(helper, south));
        helper.assertTrue(sourceSlot.isClosed(Direction.SOUTH),
                "the south side is dead, so a later placement there cannot join either");
        helper.assertTrue(sourceSlot.forcedOff.isEmpty(),
                "not because it was written down as a cut: the lock implies it");

        // The second line takes one more use, and the first one survives it.
        helper.assertTrue(WrenchLinks.link(helper.getLevel(), source, south)
                        == WrenchLinks.Result.LINKED_ON,
                "one click locks the second line as well");
        solve(helper, east);
        solve(helper, south);

        helper.succeedWhen(() -> {
            helper.assertTrue(sourceSlot.isOpen(Direction.EAST)
                            && sourceSlot.isOpen(Direction.SOUTH),
                    "both lines are locked now");
            helper.assertTrue(powerAt(helper, south) == 14,
                    "the second line carries the signal, got " + powerAt(helper, south));
            helper.assertTrue(powerAt(helper, east) == 14,
                    "and the first one still does, got " + powerAt(helper, east));
        });
    }

    /**
     * The wrench's headline: force a connection the component would never make on its own.
     *
     * <p>A repeater drives one side only, which is why it can be used to route a signal - and why
     * "细微调整走向" needs a tool. Pinning the south side makes the repeater feed a wire that is not on
     * its output side at all, which is the whole of R7's first half.
     */
    @GameTest(template = "empty")
    public void wrenchForcesAConnectionTheComponentWouldNotMake(GameTestHelper helper) {
        // Repeater host at (1,1,1), input on its west, output east. A wire sits to its south.
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 2, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 0, 2, Blocks.STONE.defaultBlockState());

        BlockPos repeater = at(helper, 1, 1, 1);
        BlockPos wire = at(helper, 1, 1, 2);
        placeComponent(helper, repeater, ComponentType.REPEATER, 15, Direction.WEST);
        Slot wireSlot = placeDust(helper, wire, 0);

        solve(helper, wire);
        helper.assertTrue(powerAt(helper, wire) == 0,
                "precondition: a repeater must not feed the side it is not pointing at, got "
                        + powerAt(helper, wire));

        WrenchLinks.Result result = WrenchLinks.link(helper.getLevel(), repeater, wire);
        helper.assertTrue(result == WrenchLinks.Result.LINKED_ON,
                "the first use of the wrench on a fresh pair pins it on, got " + result);
        solve(helper, wire);

        helper.succeedWhen(() -> {
            helper.assertTrue(slotAt(helper, repeater).forcedOn.contains(Direction.SOUTH),
                    "the clicked end must be pinned towards the neighbour");
            helper.assertTrue(wireSlot.forcedOn.contains(Direction.NORTH),
                    "and the neighbour must be pinned back, so neither can be re-routed alone");
            helper.assertTrue(powerAt(helper, wire) > 0,
                    "a pinned side now carries the repeater's output, got " + powerAt(helper, wire));
        });
    }

    /**
     * The wrench's second half: the same pair steps on to "cut", and the cut really cuts.
     *
     * <p>Clicking the same pair again is how the design says a connection is changed, so the states have
     * to be reachable in order and each one has to mean something in the world - a cut that only
     * changed the data would be found immediately and would be far more annoying than no wrench at all.
     */
    @GameTest(template = "empty")
    public void wrenchCutsAndRestoresTheSameLink(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos source = at(helper, 1, 1, 1);
        BlockPos receiver = at(helper, 2, 1, 1);
        placeDust(helper, source, 15);
        placeDust(helper, receiver, 0);

        solve(helper, receiver);
        helper.assertTrue(powerAt(helper, receiver) == 14,
                "precondition: the two wires couple on their own, got " + powerAt(helper, receiver));

        helper.assertTrue(WrenchLinks.link(helper.getLevel(), source, receiver)
                        == WrenchLinks.Result.LINKED_ON,
                "auto -> pinned on");
        solve(helper, receiver);
        helper.assertTrue(powerAt(helper, receiver) == 14,
                "a pinned link still carries the signal, got " + powerAt(helper, receiver));

        helper.assertTrue(WrenchLinks.link(helper.getLevel(), source, receiver)
                        == WrenchLinks.Result.LINKED_OFF,
                "pinned on -> cut");
        solve(helper, receiver);
        helper.assertTrue(powerAt(helper, receiver) == 0,
                "a cut link must stop the signal, got " + powerAt(helper, receiver));

        helper.assertTrue(WrenchLinks.link(helper.getLevel(), source, receiver)
                        == WrenchLinks.Result.LINKED_AUTO,
                "cut -> automatic");
        solve(helper, receiver);

        helper.succeedWhen(() -> helper.assertTrue(powerAt(helper, receiver) == 14,
                "and automatic means the network decides again, got " + powerAt(helper, receiver)));
    }

    /**
     * A line can be routed to a block that holds no redstone of its own - a piston, a lamp, a door.
     *
     * <p>Requiring a component on both ends meant putting redstone inside a piston just to be allowed to
     * point at it, which is both backwards and invisible: the wrench refused the click and said the
     * block held nothing. Now the far end only has to exist, and only the clicked end is pinned, because
     * there is nowhere to write the other override.
     */
    @GameTest(template = "empty")
    public void wrenchLocksOntoANeighbourWithoutRedstone(GameTestHelper helper) {
        // Source host at (1,1,1): a lamp east of it, a wire south of it.
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 1, 1, 2, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 0, 2, Blocks.STONE.defaultBlockState());

        BlockPos source = at(helper, 1, 1, 1);
        BlockPos lamp = at(helper, 2, 1, 1);
        BlockPos wire = at(helper, 1, 1, 2);
        Slot sourceSlot = placeDust(helper, source, 15);
        placeDust(helper, wire, 0);

        solve(helper, wire);
        helper.assertValueEqual(lampLit(helper, 2, 1, 1), true,
                "precondition: the lamp lights on its own, because a host emits to every side");
        helper.assertTrue(powerAt(helper, wire) == 14,
                "precondition: the wire couples normally, got " + powerAt(helper, wire));

        helper.assertTrue(WrenchLinks.link(helper.getLevel(), source, lamp)
                        == WrenchLinks.Result.LINKED_ON,
                "a lamp with nothing inside it can be the far end of a lock");
        solve(helper, wire);

        helper.assertTrue(sourceSlot.isOpen(Direction.EAST), "the line towards the lamp is pinned");
        helper.assertTrue(sourceSlot.isClosed(Direction.SOUTH),
                "and the wire's side is cut, because a lock keeps this line and nothing else");
        helper.assertTrue(powerAt(helper, wire) == 0,
                "so the wire goes dark, got " + powerAt(helper, wire));

        afterTicks(helper, 2, () -> helper.succeedWhen(() -> helper.assertTrue(
                lampLit(helper, 2, 1, 1),
                "while the lamp, which is the locked line's target, stays lit")));
    }

    /** The wrench refuses a pair that is not adjacent, instead of linking something arbitrary. */
    @GameTest(template = "empty")
    public void wrenchRefusesNonAdjacentPairs(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 3, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos first = at(helper, 1, 1, 1);
        BlockPos second = at(helper, 3, 1, 1);
        placeDust(helper, first, 15);
        placeDust(helper, second, 0);

        helper.assertTrue(WrenchLinks.link(helper.getLevel(), first, second)
                        == WrenchLinks.Result.NOT_ADJACENT,
                "two blocks with a gap between them have no shared face to pin");
        helper.assertTrue(WrenchLinks.link(helper.getLevel(), first, at(helper, 1, 1, 3))
                        == WrenchLinks.Result.NOT_ADJACENT,
                "and neither has a block two away with nothing in it");
        helper.assertTrue(WrenchLinks.link(helper.getLevel(), first, at(helper, 2, 1, 1))
                        == WrenchLinks.Result.NOTHING_THERE,
                "while a neighbouring empty position has nothing to lock to");
        helper.assertTrue(WrenchLinks.link(helper.getLevel(), at(helper, 0, 1, 1), first)
                        == WrenchLinks.Result.NO_COMPONENT,
                "and an empty block cannot be the end a line is routed from");
        helper.assertTrue(slotAt(helper, first).forcedOn.isEmpty()
                        && slotAt(helper, second).forcedOn.isEmpty(),
                "nothing may be written when a pair is refused");
        helper.succeed();
    }

    // ----------------------------------------------------- strong power ------

    /**
     * A repeater inside a block charges the block <b>in front of it</b>, not the one behind.
     *
     * <p>This is the vanilla rule that makes "repeater into a stone block, redstone off that block" work,
     * and getting the direction backwards is invisible to every weak-power test: weak power is asked
     * about one block at a time, while strong power is asked about the block the signal was pushed into.
     * It was backwards until this test existed, which meant a diode inside a block was quietly charging
     * the block on its input side.
     */
    @GameTest(template = "empty")
    public void repeaterStronglyPowersTheBlockInFront(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        // FACING is the input side, so the block in front is the opposite one: WEST input, EAST output.
        placeComponent(helper, host, ComponentType.REPEATER, 15, Direction.WEST);

        ServerLevel level = helper.getLevel();
        BlockState hostState = level.getBlockState(host);
        // Directions in signal methods point from the asker towards the block being asked, so a query
        // from the east is the one that asks "what do you push towards the east".
        int towardsFront = hostState.getDirectSignal(level, host, Direction.WEST);
        int towardsBack = hostState.getDirectSignal(level, host, Direction.EAST);
        int weakFront = hostState.getSignal(level, host, Direction.WEST);
        int weakBack = hostState.getSignal(level, host, Direction.EAST);

        if (towardsFront != 15) {
            helper.fail("a repeater must strongly power the block in front of it, but"
                    + " getDirectSignal towards the output side was " + towardsFront);
            return;
        }
        if (towardsBack != 0) {
            helper.fail("and must never charge the block behind it, but getDirectSignal towards the"
                    + " input side was " + towardsBack);
            return;
        }
        if (weakFront != 15 || weakBack != 0) {
            helper.fail("weak power must follow the same side: towards the output side " + weakFront
                    + ", towards the input side " + weakBack);
            return;
        }
        helper.succeed();
    }

    /**
     * And the consequence, measured in the world: a lamp on the far side of the block in front lights.
     *
     * <p>That is what strong power is <em>for</em> - the block carries it onwards - and it is the reason
     * a solid block between a repeater and a lamp is not simply an obstacle. It also covers the second
     * half of that rule, which is about notifications rather than power: the lamp is two blocks away
     * from the repeater, so nothing that notifies only the repeater's own neighbours will ever wake it.
     * Both directions are checked, because a lamp that lights but never goes out is the classic symptom
     * of a notification that happens to work one way.
     */
    @GameTest(template = "empty")
    public void repeaterDrivesALampThroughTheBlockInFront(GameTestHelper helper) {
        // Lever host at (1,1,1) feeds a repeater at (2,1,1) facing west.
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 3, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 4, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        BlockPos lever = at(helper, 1, 1, 1);
        placeComponent(helper, lever, ComponentType.LEVER, 15, Direction.NORTH);
        placeComponent(helper, at(helper, 2, 1, 1), ComponentType.REPEATER, 0, Direction.WEST);

        afterTicks(helper, 6, () -> helper.succeedWhen(() -> helper.assertTrue(
                lampLit(helper, 4, 1, 1),
                "the lamp beyond the charged block must light: strong power is carried onwards")));
    }

    /** The same lamp must go dark again when the repeater's input stops. */
    @GameTest(template = "empty")
    public void lampGoesDarkWhenTheChargedBlockIsReleased(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 3, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 4, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        BlockPos lever = at(helper, 1, 1, 1);
        Slot leverSlot = placeComponent(helper, lever, ComponentType.LEVER, 15, Direction.NORTH);
        // A lever that is delivering power has to say so: InnerSwitches.toggle flips POWERED, so a
        // lever placed "on" with power 15 but powered = false would be turned on by the next click
        // instead of off.
        leverSlot.powered = true;
        placeComponent(helper, at(helper, 2, 1, 1), ComponentType.REPEATER, 0, Direction.WEST);

        afterTicks(helper, 6, () -> {
            helper.assertTrue(lampLit(helper, 4, 1, 1),
                    "precondition: the lamp should be lit through the charged block");

            // Throw the lever back, exactly as a player would.
            InnerSwitches.toggle(helper.getLevel(), lever);
            helper.assertTrue(!leverSlot.powered, "the lever must be off now, or the rest proves nothing");

            afterTicks(helper, 8, () -> helper.succeedWhen(() -> helper.assertTrue(
                    !lampLit(helper, 4, 1, 1),
                    "and it must go out again: a lamp two blocks away still has to be told")));
        });
    }
}

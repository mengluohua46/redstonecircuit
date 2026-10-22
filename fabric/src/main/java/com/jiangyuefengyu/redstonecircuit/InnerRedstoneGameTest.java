package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.InnerSwitches;
import com.jiangyuefengyu.redstonecircuit.logic.RotationRod;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * In-world tests for inner-redstone power propagation.
 *
 * <p>These need a real level: whether two inner components couple depends on the surrounding blocks being
 * redstone conductors or transparent blocks, which cannot be modelled faithfully outside a running game.
 * The headless maths lives in {@code PowerSolverTest}. They use the {@code redstonecircuit:empty}
 * structure template - a 5x3x5 box of pure air - so every block a test needs is placed by the test itself.
 *
 * <p><b>Careful with coordinates:</b> {@code GameTestHelper#setBlock} takes a position <em>relative to the
 * structure</em>, while the inner-redstone store is keyed by absolute world positions. Mixing the two
 * silently produces a network that can never connect, so every position used for store access goes through
 * {@link #at}.
 *
 * <p>Run with {@code gradlew runGameTest}, or via {@code /test} in game.
 */
public final class InnerRedstoneGameTest implements FabricGameTest {

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
     * <p>Goes through the queue and {@link InnerRedstoneNetwork#settle} rather than calling
     * {@code recompute} directly, so the tests exercise the real path - including the neighbour updates a
     * solve sends back into the world.
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
     * <p>Succeeding from the very first tick is unreliable here: a lamp or piston only re-evaluates its
     * neighbours when a block update reaches it, and that happens during the tick following the change.
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
     * Coupling check for the solver, kept as a plain assertion.
     *
     * <p>Worth keeping because wire coupling was the single hardest part to get right: if it ever
     * regresses, this fails with the exact positions and powers involved rather than a bare
     * "expected 14 but got 0".
     */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
     * <p>Regression test for levers and torches appearing to do nothing: the inner network only re-solved
     * when a component was placed or removed by this mod, so an external block change never triggered a
     * recalculation.
     */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
     * <p>Regression test for two symptoms: a chain of components decaying towards zero while the source
     * was still present, and power staying latched after the source was removed. Both had the same cause -
     * a component treating its neighbour's <em>settled value</em> as a supply, so a pair fed off each
     * other. The rule is stated in terms of each component's own supply.
     */
    @GameTest(template = "redstonecircuit:empty")
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
     * the host's neighbour query asks for.
     */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
     */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
    public void emptyHostDoesNotLightLamp(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        placeDust(helper, at(helper, 1, 1, 1), 0);

        helper.succeedWhen(() -> helper.assertTrue(
                !helper.getBlockState(new BlockPos(2, 1, 1)).getValue(BlockStateProperties.LIT),
                "an unpowered component must not light the lamp"));
    }

    /** A component in the block above must power an adjacent lamp the same way. */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
     * The direction above a host that is easy to miss: a block above a host is driven like any other side.
     *
     * <p>Vanilla wire does not power the block above it, but that rule belongs to the wire block and not
     * to the block a component is embedded in - a host is acting as a signal source, so a lamp on top of it
     * lights like the same lamp below it.
     */
    @GameTest(template = "redstonecircuit:empty")
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
     * The drain, seen beside a host: the inner redstone drained correctly, but the redstone lamp stayed
     * lit, and only went out when an unrelated block next to it was broken.
     *
     * <p>Nothing was wrong with the solver - the stored power really did fall to 0. The problem was that a
     * stored value is not a block state: dropping it changed no world data, so nothing notified the lamp,
     * and it kept whatever state it happened to hold. A solve therefore ends by sending a vanilla neighbour
     * update for every host whose power changed.
     */
    @GameTest(template = "redstonecircuit:empty")
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
     * The same drain, seen through vanilla redstone wire laid against the host block.
     *
     * <p>Also the regression test for the loop the wire creates: the wire is powered by the host and the
     * host reads the wire, so treating the wire as a full-strength supply would let the two hold each
     * other up at 15 forever. <b>Both</b> directions are charged one hop - the host reading the wire
     * ({@code InnerRedstoneNetwork#neighbourSignal}), and the wire reading the host
     * ({@code BlockSignalSources#hopChargedByReader}) - which leaves the pair no way to stay powered on
     * their own: with the injected supply taken away they fade together to zero.
     *
     * <p>The precondition below pins the wire at 14 rather than 15 for that reason: a buried dust is still
     * a dust, so a dust beside it costs one, exactly as a dust beside a dust does on the ground.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void vanillaWireFollowsInnerPower(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 0, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_WIRE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        placeDust(helper, host, 15);

        afterTicks(helper, 2, () -> {
            helper.assertTrue(wirePower(helper, 2, 1, 1) == 14,
                    "precondition: the wire beside the host at 15 should carry 14 - a dust beside a "
                            + "dust costs one hop, got " + wirePower(helper, 2, 1, 1));

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
    @GameTest(template = "redstonecircuit:empty")
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
     * <p>Sources that emit in five or six directions - levers, torches, redstone blocks, wire - cannot
     * reveal a reversed direction argument, because the wrong side still answers 15. A repeater answers
     * only for the side it points at, so with the direction reversed it reports nothing at all.
     */
    @GameTest(template = "redstonecircuit:empty")
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
     * <b>An observer facing a host block must SEE the inner redstone change.</b>
     *
     * <p>An observer does not watch signals, it watches <b>state changes</b>: {@code ObserverBlock} pulses
     * from {@code updateShape} alone, and vanilla sends that when a block's state changes. A host block is
     * stone before and stone after, so nothing is sent and the observer stays silent however much the
     * inner signal moves - while every redstone consumer (which asks {@code getSignal}) sees it fine.
     * {@code InnerRedstoneNetwork#notifyNeighboursNow} therefore sends the shape update beside the
     * neighbour update; this is the regression test for that one call.
     *
     * <p>A block's {@code FACING} points at what an observer watches, so the observer sits WEST of the host
     * and faces EAST (the reverse of the test below, which points an observer into a host). The pulse is
     * armed <em>before</em> the change - an observer stays powered for two ticks - and the change is a
     * component appearing in the host at full strength, which is the placement path's own notification.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void anObserverFacingAHostSeesTheInnerRedstoneChange(GameTestHelper helper) {
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        BlockPos host = at(helper, 2, 1, 1);

        setBlock(helper, 1, 1, 1, Blocks.OBSERVER.defaultBlockState()
                .setValue(BlockStateProperties.FACING, Direction.EAST));

        // Placed OFF and then flipped: a fixture that CREATES the component at 15 never changes an output,
        // so nothing is ever reported to the world and an observer could not pulse even on a correct fix.
        placeComponent(helper, host, ComponentType.LEVER, 0, Direction.NORTH);

        helper.succeedWhen(() -> {
            BlockState observer = helper.getBlockState(new BlockPos(1, 1, 1));
            helper.assertTrue(observer.is(Blocks.OBSERVER),
                    "the observer must still be at its own position, found " + observer);
            helper.assertTrue(observer.getValue(BlockStateProperties.POWERED),
                    "the inner redstone in the block the observer faces changed; the observer must pulse");
        });

        // The inner signal appears: 0 -> 15 on the component inside the host, which is an output change
        // reported to the world exactly like flipping a lever that was already there.
        InnerSwitches.toggle(helper.getLevel(), host);
    }

    /**
     * An observer pressed against a host block, emitting into it, powers the redstone inside - no vanilla
     * wire needed in between.
     *
     * <p>{@code ObserverBlock#getSignal} answers 15 only for the side it emits from, so a reversed
     * direction argument makes it report nothing while a wire laid against the host works (a wire emits in
     * five directions). That is the "the observer against the block does nothing, it needs a piece of
     * redstone in between" case.
     */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
     * <p>A lever is not wire, so the dust it feeds holds the full 15 rather than 14 - exactly as a lever
     * does against a piece of vanilla wire.
     */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
     * A repeater switches off again when its input stops, even with a solid block on its input side.
     *
     * <p>The query that decides the switch-off is where this matters. A neighbouring solid block reports
     * the strong power it receives, a host block is a signal source, and the query run at the scheduled
     * tick must ignore our own output - otherwise a repeater's input side reads its own 15 back through
     * the stone next door, its input never drops, and the pending switch-off is discarded as "computes to
     * the same value".
     *
     * <p>The layout matters: the input side has to be a solid host block for the strong-power path to
     * exist, where switching an input on would read your own output back with the same value the input
     * already had.
     */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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

    /**
     * The wire form: laid on the ground it behaves like redstone, except that it never fades.
     *
     * <p>This is the block form of the same material, and it is vanilla's wire block underneath - so the
     * test is about the one thing that was changed. A redstone block feeds the row, and the checked
     * strengths are read out of the blockstates the world actually holds, which is what the player sees.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void placedSuperconductingWireDoesNotFade(GameTestHelper helper) {
        // A floor for the wires to lie on, then a source and four blocks of wire.
        for (int x = 0; x <= 4; x++) {
            setBlock(helper, x, 0, 1, Blocks.STONE.defaultBlockState());
        }
        setBlock(helper, 0, 1, 1, Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int x = 1; x <= 4; x++) {
            setBlock(helper, x, 1, 1, RCRegistry.SUPERCONDUCTING_WIRE.defaultBlockState());
        }

        afterTicks(helper, 3, () -> helper.succeedWhen(() -> {
            for (int x = 1; x <= 4; x++) {
                int power = wirePowerAt(helper, x, 1, 1);
                helper.assertTrue(power == 15,
                        "superconducting wire at x=" + x + " is fed at 15 and loses nothing, got " + power);
            }
        }));
    }

    /** The control: ordinary wire on the same floor fades a step per block. */
    @GameTest(template = "redstonecircuit:empty")
    public void placedOrdinaryWireStillFades(GameTestHelper helper) {
        for (int x = 0; x <= 4; x++) {
            setBlock(helper, x, 0, 1, Blocks.STONE.defaultBlockState());
        }
        setBlock(helper, 0, 1, 1, Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int x = 1; x <= 4; x++) {
            setBlock(helper, x, 1, 1, Blocks.REDSTONE_WIRE.defaultBlockState());
        }

        afterTicks(helper, 3, () -> helper.succeedWhen(() -> {
            for (int x = 1; x <= 4; x++) {
                int expected = 15 - (x - 1);
                int power = wirePowerAt(helper, x, 1, 1);
                helper.assertTrue(power == expected,
                        "ordinary wire at x=" + x + " should be " + expected + ", got " + power);
            }
        }));
    }

    /**
     * The mixing rule, on the ground this time: no hop into the superconductor, one hop out of it.
     *
     * <p>That is the same rule the buried kind follows, and it falls out of the same reasoning - each wire
     * charges its own hop, and the superconductor's is nothing. Keeping the two consistent is the point:
     * the material is the same either way.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void placedWiresMixLikeTheBuriedOnes(GameTestHelper helper) {
        for (int x = 0; x <= 3; x++) {
            setBlock(helper, x, 0, 1, Blocks.STONE.defaultBlockState());
        }
        // redstone block -> dust -> superconductor -> dust
        setBlock(helper, 0, 1, 1, Blocks.REDSTONE_BLOCK.defaultBlockState());
        setBlock(helper, 1, 1, 1, Blocks.REDSTONE_WIRE.defaultBlockState());
        setBlock(helper, 2, 1, 1, RCRegistry.SUPERCONDUCTING_WIRE.defaultBlockState());
        setBlock(helper, 3, 1, 1, Blocks.REDSTONE_WIRE.defaultBlockState());

        afterTicks(helper, 3, () -> helper.succeedWhen(() -> {
            helper.assertTrue(wirePowerAt(helper, 1, 1, 1) == 15,
                    "the source lights the first dust at full strength, got " + wirePowerAt(helper, 1, 1, 1));
            helper.assertTrue(wirePowerAt(helper, 2, 1, 1) == 15,
                    "dust feeding the superconductor charges nothing for the hop, got "
                            + wirePowerAt(helper, 2, 1, 1));
            helper.assertTrue(wirePowerAt(helper, 3, 1, 1) == 14,
                    "and dust on the far side fades by one, as it always does, got "
                            + wirePowerAt(helper, 3, 1, 1));
        }));
    }

    /**
     * The lossless wire case, in the world: switching the lever off left the wire at fifteen, still
     * lighting what it touched.
     *
     * <p>A lossless wire cannot be decided from its neighbours' current values the way vanilla decides a
     * fading one, because two wires at fifteen see nothing wrong with each other. The run therefore has to
     * be solved from its sources: with the source gone, there is nothing left in the run to hold it up.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void placedWireGoesDarkWhenItsSourceStops(GameTestHelper helper) {
        for (int x = 0; x <= 3; x++) {
            setBlock(helper, x, 0, 1, Blocks.STONE.defaultBlockState());
        }
        // A lever on the floor beside the wire, the shape that produces the lossless run.
        setBlock(helper, 0, 1, 1, Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.POWERED, true));
        for (int x = 1; x <= 3; x++) {
            setBlock(helper, x, 1, 1, RCRegistry.SUPERCONDUCTING_WIRE.defaultBlockState());
        }

        afterTicks(helper, 3, () -> {
            for (int x = 1; x <= 3; x++) {
                helper.assertTrue(wirePowerAt(helper, x, 1, 1) == 15,
                        "precondition: the lever lights the run, but x=" + x + " is "
                                + wirePowerAt(helper, x, 1, 1));
            }

            // Switch the lever off, exactly as a player would.
            setBlock(helper, 0, 1, 1, helper.getBlockState(new BlockPos(0, 1, 1))
                    .setValue(BlockStateProperties.POWERED, false));

            afterTicks(helper, 3, () -> helper.succeedWhen(() -> {
                for (int x = 1; x <= 3; x++) {
                    int power = wirePowerAt(helper, x, 1, 1);
                    helper.assertTrue(power == 0,
                            "with the lever off the whole run must go dark, but x=" + x + " is " + power);
                }
            }));
        });
    }

    /** The same for a run buried inside blocks, which the inner solver is responsible for. */
    @GameTest(template = "redstonecircuit:empty")
    public void buriedSuperconductingDustGoesDarkWhenItsLeverIsOff(GameTestHelper helper) {
        for (int x = 0; x <= 2; x++) {
            setBlock(helper, x, 1, 1, Blocks.STONE.defaultBlockState());
        }

        BlockPos lever = at(helper, 0, 1, 1);
        Slot leverSlot = placeComponent(helper, lever, ComponentType.LEVER, 15, Direction.NORTH);
        leverSlot.powered = true;
        placeComponent(helper, at(helper, 1, 1, 1), ComponentType.SUPERCONDUCTOR, 0, Direction.NORTH);
        placeComponent(helper, at(helper, 2, 1, 1), ComponentType.SUPERCONDUCTOR, 0, Direction.NORTH);

        solve(helper, at(helper, 2, 1, 1));
        helper.assertTrue(powerAt(helper, at(helper, 1, 1, 1)) == 15
                        && powerAt(helper, at(helper, 2, 1, 1)) == 15,
                "precondition: the lever lights the buried run");

        InnerSwitches.toggle(helper.getLevel(), lever);
        solve(helper, at(helper, 2, 1, 1));

        helper.assertTrue(powerAt(helper, at(helper, 1, 1, 1)) == 0
                        && powerAt(helper, at(helper, 2, 1, 1)) == 0,
                "and burying the run must not change that: got "
                        + powerAt(helper, at(helper, 1, 1, 1)) + " and "
                        + powerAt(helper, at(helper, 2, 1, 1)));
        helper.succeed();
    }

    // -------------------------- a placed wire and the dust buried beside it --

    /** A lever standing on the floor, switched on or off - the source these four tests switch. */
    private static BlockState floorLever(boolean powered) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE,
                        net.minecraft.world.level.block.state.properties.AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.POWERED, powered);
    }

    /**
     * 地面超导红石粉 beside a host block holding <b>superconducting</b> dust: switching the source off has to
     * darken both sides.
     *
     * <p>They used to latch at fifteen for ever. Each side is solved from its own sources and reads the
     * other's <b>stored</b> value, and neither of the two boundaries charged a hop - a lossless wire on both
     * sides of one boundary is a loop of gain exactly one, so <em>every</em> pair of values was a fixed point
     * and fifteen stayed fifteen with the lever off. Neither solver is wrong on its own; the coupling was.
     * The two sides are one electrical run, so they are solved as one, from zero.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void placedWireAndBuriedWireGoDarkTogether(GameTestHelper helper) {
        for (int x = 0; x <= 3; x++) {
            setBlock(helper, x, 0, 1, Blocks.STONE.defaultBlockState());
        }
        // The host block with superconducting dust inside, the wire lying beside it, and the lever feeding them.
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        placeComponent(helper, at(helper, 1, 1, 1), ComponentType.SUPERCONDUCTOR, 0, Direction.NORTH);
        setBlock(helper, 2, 1, 1, RCRegistry.SUPERCONDUCTING_WIRE.defaultBlockState());
        setBlock(helper, 3, 1, 1, floorLever(true));

        afterTicks(helper, 4, () -> {
            helper.assertTrue(wirePowerAt(helper, 2, 1, 1) == 15,
                    "precondition: the lever lights the placed wire, got " + wirePowerAt(helper, 2, 1, 1));
            helper.assertTrue(powerAt(helper, at(helper, 1, 1, 1)) == 15,
                    "precondition: and the buried superconductor with it, got "
                            + powerAt(helper, at(helper, 1, 1, 1)));

            setBlock(helper, 3, 1, 1, floorLever(false));

            afterTicks(helper, 4, () -> helper.succeedWhen(() -> {
                helper.assertTrue(wirePowerAt(helper, 2, 1, 1) == 0,
                        "with the lever off the placed wire must go dark, got "
                                + wirePowerAt(helper, 2, 1, 1));
                helper.assertTrue(powerAt(helper, at(helper, 1, 1, 1)) == 0,
                        "and the buried superconductor must go dark with it, got "
                                + powerAt(helper, at(helper, 1, 1, 1)));
            }));
        });
    }

    /**
     * The same with <b>ordinary</b> dust inside the host block: the latch came from the placed side, so the
     * material buried in the block makes no difference.
     *
     * <p>The buried dust settles at fourteen rather than fifteen, and that is the rule rather than a
     * compromise: a hop is charged to the wire that <em>receives</em> it, the placed run is superconducting
     * and charges nothing, so the dust pays its own hop on the way in - exactly what dust on the far side of
     * a superconducting run costs inside one block.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void placedWireAndBuriedOrdinaryDustGoDarkTogether(GameTestHelper helper) {
        for (int x = 0; x <= 3; x++) {
            setBlock(helper, x, 0, 1, Blocks.STONE.defaultBlockState());
        }
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 1, 1, 1), 0);
        setBlock(helper, 2, 1, 1, RCRegistry.SUPERCONDUCTING_WIRE.defaultBlockState());
        setBlock(helper, 3, 1, 1, floorLever(true));

        afterTicks(helper, 4, () -> {
            helper.assertTrue(wirePowerAt(helper, 2, 1, 1) == 15,
                    "precondition: the lever lights the placed wire, got " + wirePowerAt(helper, 2, 1, 1));
            helper.assertTrue(powerAt(helper, at(helper, 1, 1, 1)) == 14,
                    "precondition: and the buried dust takes fourteen - it pays its own hop - got "
                            + powerAt(helper, at(helper, 1, 1, 1)));

            setBlock(helper, 3, 1, 1, floorLever(false));

            afterTicks(helper, 4, () -> helper.succeedWhen(() -> {
                helper.assertTrue(wirePowerAt(helper, 2, 1, 1) == 0,
                        "with the lever off the placed wire must go dark, got "
                                + wirePowerAt(helper, 2, 1, 1));
                helper.assertTrue(powerAt(helper, at(helper, 1, 1, 1)) == 0,
                        "and the buried dust must go dark with it, got "
                                + powerAt(helper, at(helper, 1, 1, 1)));
            }));
        });
    }

    /**
     * The positive half of the same rule: a source on the <b>inner</b> side fills the placed wire to fifteen.
     *
     * <p>This is what separates the merged solve from "charge the boundary one hop": a superconductor loses
     * nothing across the edge of the block either, so the wire gets the full fifteen rather than fourteen.
     *
     * <p>Fifteen both before and after the two sides are solved as one: the pair above is fixed by solving them
     * together, never by charging the boundary a hop, and this is the reading that would catch such a change.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void buriedSuperconductorFillsPlacedWireInFull(GameTestHelper helper) {
        for (int x = 0; x <= 3; x++) {
            setBlock(helper, x, 0, 1, Blocks.STONE.defaultBlockState());
        }
        // lever host -> superconductor host -> the wire on the ground beside it
        for (int x = 0; x <= 2; x++) {
            setBlock(helper, x, 1, 1, Blocks.STONE.defaultBlockState());
        }
        placeComponent(helper, at(helper, 0, 1, 1), ComponentType.LEVER, 15, Direction.NORTH);
        placeComponent(helper, at(helper, 1, 1, 1), ComponentType.SUPERCONDUCTOR, 0, Direction.NORTH);
        setBlock(helper, 2, 1, 1, RCRegistry.SUPERCONDUCTING_WIRE.defaultBlockState());

        afterTicks(helper, 4, () -> helper.succeedWhen(() -> {
            helper.assertTrue(powerAt(helper, at(helper, 1, 1, 1)) == 15,
                    "the lever lights the buried superconductor, got "
                            + powerAt(helper, at(helper, 1, 1, 1)));
            helper.assertTrue(wirePowerAt(helper, 2, 1, 1) == 15,
                    "and the wire on the ground beside it takes the full fifteen - a superconductor costs "
                            + "nothing across the edge of the block either, got "
                            + wirePowerAt(helper, 2, 1, 1));
        }));
    }

    /**
     * The same in the other direction: a source on the ground fills the buried superconductor to fifteen.
     *
     * <p>Fifteen again, whichever side the source is on: what reaches a run across the edge of a host block is
     * the same number in both directions, and no coupling between the two sides may take a step off it.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void placedWireFillsBuriedSuperconductorInFull(GameTestHelper helper) {
        for (int x = 0; x <= 3; x++) {
            setBlock(helper, x, 0, 1, Blocks.STONE.defaultBlockState());
        }
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        placeComponent(helper, at(helper, 1, 1, 1), ComponentType.SUPERCONDUCTOR, 0, Direction.NORTH);
        setBlock(helper, 2, 1, 1, RCRegistry.SUPERCONDUCTING_WIRE.defaultBlockState());
        setBlock(helper, 3, 1, 1, Blocks.REDSTONE_BLOCK.defaultBlockState());

        afterTicks(helper, 4, () -> helper.succeedWhen(() -> {
            helper.assertTrue(wirePowerAt(helper, 2, 1, 1) == 15,
                    "the redstone block lights the placed wire, got " + wirePowerAt(helper, 2, 1, 1));
            helper.assertTrue(powerAt(helper, at(helper, 1, 1, 1)) == 15,
                    "and the buried superconductor takes the full fifteen across the edge of the block, got "
                            + powerAt(helper, at(helper, 1, 1, 1)));
        }));
    }

    /** The strength a wire block in the world currently holds. */
    private static int wirePowerAt(GameTestHelper helper, int x, int y, int z) {
        BlockState state = helper.getBlockState(new BlockPos(x, y, z));
        if (!state.hasProperty(BlockStateProperties.POWER)) {
            return -1;
        }
        return state.getValue(BlockStateProperties.POWER);
    }

    /**
     * Breaking the placed wire gives it back.
     *
     * <p>A wire's drop comes from a loot table rather than from code, and a loot table that fails to load
     * leaves the block silently dropping nothing - which is the sort of thing that is only noticed after
     * losing a stack of the material.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void placedWireDropsItself(GameTestHelper helper) {
        setBlock(helper, 1, 0, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 1, RCRegistry.SUPERCONDUCTING_WIRE.defaultBlockState());

        // Broken the way a player breaks it - dropping its loot. GameTestHelper#destroyBlock passes
        // drop = false, which would prove nothing here.
        helper.getLevel().destroyBlock(helper.absolutePos(new BlockPos(1, 1, 1)), true, null);

        helper.assertItemEntityPresent(RCRegistry.SUPERCONDUCTING_REDSTONE);
        helper.succeed();
    }

    /**
     * A comparator placed on a block's top face inside a block, in the world.
     *
     * <p>{@code Direction#getClockWise} throws for up and down, and the comparator asked for its sides
     * that way as soon as it was ticked - taking the server down. This test only has to place one and let
     * the tick handler run: if the throw comes back, the test fails.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void verticalComparatorDoesNotCrashTheServer(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 2, Blocks.STONE.defaultBlockState());

        // Facing UP: placed on the top face, so it reads from above and drives downwards.
        placeComponent(helper, at(helper, 1, 1, 1), ComponentType.COMPARATOR, 0, Direction.UP);
        // A back input of 8 from above, and two horizontal sides to compare against.
        placeDust(helper, at(helper, 1, 2, 1), 8);
        placeDust(helper, at(helper, 1, 1, 2), 6);
        placeDust(helper, at(helper, 2, 1, 1), 3);

        // Both paths are exercised: settling the network, and the scheduled tick - hence the ticks below.
        solve(helper, at(helper, 1, 1, 1));
        tick(helper);
        tick(helper);
        tick(helper);

        Slot comparator = slotAt(helper, at(helper, 1, 1, 1));
        helper.assertTrue(comparator != null,
                "the comparator must still be there after being ticked");
        afterTicks(helper, 4, () -> helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, at(helper, 1, 1, 1)) == 8,
                "and it should pass its back input of 8 through, having read both sides, got "
                        + powerAt(helper, at(helper, 1, 1, 1)))));
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
    @GameTest(template = "redstonecircuit:empty")
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
     * <b>The comparator inside a block reads the block beside it, a cake included.</b>
     *
     * <p>A cake pushes no redstone signal at all, so an inner comparator that only read signals would read
     * 0 from it; vanilla reads a comparator's back input as the neighbour block's <em>analogue output</em>,
     * which for a cake is one per remaining bite. This test builds exactly that: a host block with a
     * comparator inside it, a cake next to the input side, and asks the solver.
     *
     * <p>The expected value is read from the game itself
     * ({@code BlockState#getAnalogOutputSignal}) rather than written down, so the fixture cannot agree with
     * a wrong constant.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void anInnerComparatorReadsACakeBesideItsBlock(GameTestHelper helper) {
        setBlock(helper, 2, 1, 2, Blocks.STONE.defaultBlockState());
        BlockPos host = at(helper, 2, 1, 2);
        // The comparator's input side is its `facing`, so WEST means the block it reads is the one west
        // of the host - where the cake goes.
        placeComponent(helper, host, ComponentType.COMPARATOR, 0, Direction.WEST);

        setBlock(helper, 1, 1, 2, Blocks.CAKE.defaultBlockState().setValue(CakeBlock.BITES, 2));
        int expected = helper.getLevel().getBlockState(at(helper, 1, 1, 2))
                .getAnalogOutputSignal(helper.getLevel(), at(helper, 1, 1, 2));
        helper.assertTrue(expected > 0, "the fixture must be a cake that really has an analogue output, "
                + "or this test would pass on a comparator that reads nothing");

        afterTicks(helper, 4, () -> {
            solve(helper, host);
            helper.assertTrue(powerAt(helper, host) == expected,
                    "the inner comparator must read the cake beside its block: expected " + expected
                            + ", got " + powerAt(helper, host));
            helper.succeed();
        });
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
    @GameTest(template = "redstonecircuit:empty")
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
     * its output side at all.
     */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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
     * A repeater drives the block in front of it and nothing else - not its sides, not up or down.
     *
     * <p>The front and back pair is covered above; this covers the other four, because "the repeater
     * seems to signal sideways" is what a player reports when something beside a repeater's host block
     * reacts, and what such a block can see is either the weak or the strong answer of that direction.
     * A query is answered for the direction the asker is in and the signal travels the other way, so the
     * output side (south, for a repeater reading from the north) is asked about by a query from the
     * north.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void innerRepeaterOnlyDrivesTheBlockInFront(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        // FACING is the input side: this one reads from the north and drives the south.
        placeComponent(helper, host, ComponentType.REPEATER, 15, Direction.NORTH);

        ServerLevel level = helper.getLevel();
        BlockState state = level.getBlockState(host);

        for (Direction from : new Direction[] { Direction.SOUTH, Direction.EAST, Direction.WEST,
                Direction.UP, Direction.DOWN }) {
            int weak = state.getSignal(level, host, from);
            int strong = state.getDirectSignal(level, host, from);
            if (weak != 0 || strong != 0) {
                helper.fail("a repeater reading from the north drives the south and nothing else, but a"
                        + " query from the " + from + " saw getSignal=" + weak
                        + " and getDirectSignal=" + strong);
                return;
            }
        }

        int front = state.getSignal(level, host, Direction.NORTH);
        int frontStrong = state.getDirectSignal(level, host, Direction.NORTH);
        if (front != 15 || frontStrong != 15) {
            helper.fail("the block in front should see 15 weak and strongly, but saw " + front
                    + " and " + frontStrong);
            return;
        }
        helper.succeed();
    }

    /**
     * And the same thing seen in the world: lamps all round a repeater's host block, and only the one in
     * front may light.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void innerRepeaterLightsOnlyTheLampInFront(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());   // east  - a side
        setBlock(helper, 0, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());   // west  - a side
        setBlock(helper, 1, 2, 1, Blocks.REDSTONE_LAMP.defaultBlockState());   // above - a side
        setBlock(helper, 1, 1, 2, Blocks.REDSTONE_LAMP.defaultBlockState());   // south - the front

        BlockPos host = at(helper, 1, 1, 1);
        placeComponent(helper, host, ComponentType.REPEATER, 15, Direction.NORTH);
        // A repeater is a receiver: without something driving its input it settles back to silence, so
        // feed the north side the way a player would.
        placeDust(helper, at(helper, 1, 1, 0), 15);
        tick(helper);

        afterTicks(helper, 6, () -> helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(new BlockPos(1, 1, 2))
                    .getValue(BlockStateProperties.LIT), "the lamp in front should be lit");
            helper.assertTrue(!helper.getBlockState(new BlockPos(2, 1, 1))
                    .getValue(BlockStateProperties.LIT), "the lamp to the east should stay dark");
            helper.assertTrue(!helper.getBlockState(new BlockPos(0, 1, 1))
                    .getValue(BlockStateProperties.LIT), "the lamp to the west should stay dark");
            helper.assertTrue(!helper.getBlockState(new BlockPos(1, 2, 1))
                    .getValue(BlockStateProperties.LIT), "the lamp above should stay dark");
        }));
    }

    /**
     * Two repeaters in a row must not light anything beside the first one.
     *
     * <p>Jump two repeaters and the world beside the first one must stay dark. The rule that makes it
     * matter is vanilla's own rule for <em>conductors</em> - {@code SignalGetter}
     * answers a query against an opaque block with {@code max(its own signal, the strong power pushed
     * into it)} - and a host block is a block of stone. So a repeater pointing into another repeater's
     * block would charge that block, and every side of it would light up.
     *
     * <p>That is right for a block of stone. It is wrong for a host block, because the block <em>is</em>
     * the component: a repeater inside a block should hand its signal to what is next to it, not turn
     * its neighbour's block into a redstone block.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aRepeaterInARowDoesNotPowerBesideTheFirstOne(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        // Beside and above the first repeater's block: stone would light all three.
        setBlock(helper, 1, 1, 0, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 1, 1, 2, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 1, 2, 1, Blocks.REDSTONE_LAMP.defaultBlockState());
        // ...and one in front of it, where its own output really does go.
        setBlock(helper, 0, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());

        // Both read from the east, as the player's did, so B drives A and A drives the lamp to its west.
        placeComponent(helper, at(helper, 1, 1, 1), ComponentType.REPEATER, 0, Direction.EAST);
        placeComponent(helper, at(helper, 2, 1, 1), ComponentType.REPEATER, 0, Direction.EAST);
        placeDust(helper, at(helper, 3, 1, 1), 15);
        tick(helper);

        afterTicks(helper, 10, () -> helper.succeedWhen(() -> {
            helper.assertTrue(slotAt(helper, at(helper, 1, 1, 1)).power == 15,
                    "the second repeater must still drive the first one");
            helper.assertTrue(helper.getBlockState(new BlockPos(0, 1, 1))
                    .getValue(BlockStateProperties.LIT), "and the lamp in front of it should light");
            helper.assertTrue(!helper.getBlockState(new BlockPos(1, 1, 0))
                    .getValue(BlockStateProperties.LIT), "the lamp to its north must stay dark");
            helper.assertTrue(!helper.getBlockState(new BlockPos(1, 1, 2))
                    .getValue(BlockStateProperties.LIT), "the lamp to its south must stay dark");
            helper.assertTrue(!helper.getBlockState(new BlockPos(1, 2, 1))
                    .getValue(BlockStateProperties.LIT), "the lamp above it must stay dark");
        }));
    }

    /**
     * A repeater inside a block charges the block <b>in front of it</b>, not the one behind.
     *
     * <p>This is the vanilla rule that makes "repeater into a stone block, redstone off that block" work,
     * and getting the direction backwards is invisible to every weak-power test: weak power is asked
     * about one block at a time, while strong power is asked about the block the signal was pushed into.
     * It was backwards until this test existed, which meant a diode inside a block was quietly charging
     * the block on its input side.
     */
    @GameTest(template = "redstonecircuit:empty")
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
     * And the consequence in the world: a lamp on the far side of the block in front lights.
     *
     * <p>That is what strong power is <em>for</em> - the block carries it onwards - and it is the reason
     * a solid block between a repeater and a lamp is not simply an obstacle. It also covers the second
     * half of that rule, which is about notifications rather than power: the lamp is two blocks away
     * from the repeater, so nothing that notifies only the repeater's own neighbours will ever wake it.
     * Both directions are checked, because a lamp that lights but never goes out is the classic symptom
     * of a notification that happens to work one way.
     */
    @GameTest(template = "redstonecircuit:empty")
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
    @GameTest(template = "redstonecircuit:empty")
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

    // ------------------------------------------------- 1.1.0 components -----

    /**
     * A retaining repeater hands on the strength it was given, where a plain repeater would restore it.
     *
     * <p>The same slot is then turned into a plain repeater without moving a block, so the difference
     * is proved against the identical circuit rather than against a second, separately built one.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void retainingRepeaterKeepsTheStrength(GameTestHelper helper) {
        for (int x = 1; x <= 3; x++) {
            setBlock(helper, x, 1, 1, Blocks.STONE.defaultBlockState());
        }

        BlockPos supply = at(helper, 1, 1, 1);
        BlockPos repeater = at(helper, 2, 1, 1);
        placeDust(helper, supply, 7);
        Slot slot = placeComponent(helper, repeater, ComponentType.RETAINING_REPEATER, 0, Direction.WEST);

        afterTicks(helper, 6, () -> {
            helper.assertTrue(powerAt(helper, repeater) == 7,
                    "seven in must be seven out, got " + powerAt(helper, repeater));

            // Same circuit, same supply - only the part changes.
            slot.type = ComponentType.REPEATER;
            InnerRedstoneNetwork.markDirtyWithNeighbours(helper.getLevel(), repeater);

            afterTicks(helper, 8, () -> helper.succeedWhen(() -> helper.assertTrue(
                    powerAt(helper, repeater) == 15,
                    "a plain repeater restores the same seven to fifteen, got "
                            + powerAt(helper, repeater))));
        });
    }

    /**
     * A double repeater takes twice as long to switch: four ticks where the vanilla setting is two.
     *
     * <p>The mid-flight assertion is what proves the doubling - at two ticks a plain repeater has
     * already switched, so a double one still reading zero there cannot be running at vanilla speed.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void doubleRepeaterTakesTwiceAsLong(GameTestHelper helper) {
        for (int x = 1; x <= 3; x++) {
            setBlock(helper, x, 1, 1, Blocks.STONE.defaultBlockState());
        }

        placeDust(helper, at(helper, 1, 1, 1), 15);
        BlockPos repeater = at(helper, 2, 1, 1);
        placeComponent(helper, repeater, ComponentType.DOUBLE_REPEATER, 0, Direction.WEST);

        afterTicks(helper, 2, () -> helper.assertTrue(powerAt(helper, repeater) == 0,
                "four ticks of delay, so at two it must still be waiting, got "
                        + powerAt(helper, repeater)));

        afterTicks(helper, 10, () -> helper.succeedWhen(() -> helper.assertTrue(
                powerAt(helper, repeater) == 15,
                "and by ten ticks it must have switched on, got " + powerAt(helper, repeater))));
    }

    /**
     * The weather detector answers clear weather, can be flipped by hand, and keeps re-checking by itself.
     *
     * <p>A game test level never rains - {@code setWeatherParameters} leaves {@code isRaining()} false - so
     * the setting that watches clear weather is the one that can be observed here. That still covers the
     * whole mechanism: answering as soon as it is placed, writing the answer into the block state, and
     * noticing that the answer no longer matches without anybody telling it.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void weatherDetectorAnswersAndKeepsChecking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        setBlock(helper, 1, 2, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos pos = at(helper, 1, 1, 1);
        setBlock(helper, 1, 1, 1, RCRegistry.WEATHER_DETECTOR.defaultBlockState());

        // Buried under stone, on the default setting: it must answer at once, without a view of the sky.
        helper.assertTrue(level.getBlockState(pos).getValue(WeatherDetectorBlock.ACTIVE),
                "in clear weather, a detector watching for clear weather must light up as it lands");
        helper.assertTrue(level.getSignal(pos, Direction.UP) == 15,
                "and answer a full fifteen, got " + level.getSignal(pos, Direction.UP));

        // The plate is six pixels tall. A full-cube shape would make the game cull the touching faces of
        // the block beside it and the one above, leaving a see-through hole in the wall behind them.
        helper.assertTrue(!Block.isShapeFullBlock(level.getBlockState(pos).getShape(level, pos)),
                "the plate must not pretend to fill its block");
        helper.assertTrue(!level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP),
                "so the block above keeps its bottom face and the world stays opaque");

        // Right-click it the way a player would: every click walks the setting on to the next weather,
        // and the answer follows. Three settings, so the cycle is 晴 → 雨 → 雷 → 晴.
        Runnable click = () -> level.getBlockState(pos).useWithoutItem(level,
                helper.makeMockPlayer(GameType.CREATIVE),
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));

        click.run();
        helper.assertTrue(level.getBlockState(pos).getValue(WeatherDetectorBlock.WEATHER)
                == WeatherDetectorBlock.Mode.RAIN, "the first click must leave it watching for rain");
        helper.assertTrue(!level.getBlockState(pos).getValue(WeatherDetectorBlock.ACTIVE),
                "and put the signal out, since it is not raining");

        click.run();
        helper.assertTrue(level.getBlockState(pos).getValue(WeatherDetectorBlock.WEATHER)
                == WeatherDetectorBlock.Mode.THUNDER, "the second must leave it watching for thunder");
        helper.assertTrue(!level.getBlockState(pos).getValue(WeatherDetectorBlock.ACTIVE),
                "which is silent in clear weather too - thunder is a setting of its own, not \"rain\"");

        click.run();
        helper.assertTrue(level.getBlockState(pos).getValue(WeatherDetectorBlock.WEATHER)
                == WeatherDetectorBlock.Mode.CLEAR, "and the third comes back round to clear weather");
        helper.assertTrue(level.getBlockState(pos).getValue(WeatherDetectorBlock.ACTIVE),
                "which is the one setting that answers right now");

        // Now lie to it: store the wrong answer and wait. Only the block's own polling can put that
        // right - and it has to, or a detector would stay stuck after the weather changed. The setting
        // is clear weather here, so the wrong answer is "off" and the poll has to switch it back on.
        level.setBlock(pos, level.getBlockState(pos).setValue(WeatherDetectorBlock.ACTIVE, false), 3);

        afterTicks(helper, 30, () -> helper.succeedWhen(() -> {
            helper.assertTrue(level.getBlockState(pos).getValue(WeatherDetectorBlock.ACTIVE),
                    "the poll must have corrected the answer nobody else updates");
            helper.assertTrue(level.getSignal(pos, Direction.UP) == 15,
                    "so the signal must be back, got " + level.getSignal(pos, Direction.UP));
        }));
    }

    /**
     * A bare hand steps the delay of every repeater we ship, not only the vanilla one.
     *
     * <p>Regression: the three new repeaters were missing from the switch that steps a delay, so a
     * player could put one inside a block and then never change its setting.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void everyRepeaterCanBeAdjustedByHand(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        BlockPos pos = at(helper, 1, 1, 1);
        ServerLevel level = helper.getLevel();

        for (ComponentType type : new ComponentType[] { ComponentType.REPEATER,
                ComponentType.RETAINING_REPEATER, ComponentType.DOUBLE_REPEATER,
                ComponentType.DOUBLE_RETAINING_REPEATER }) {
            placeComponent(helper, pos, type, 0, Direction.WEST);

            for (int setting = 2; setting <= 4; setting++) {
                helper.assertTrue(InnerSwitches.toggle(level, pos) == InnerSwitches.Result.ADJUSTED,
                        type + " must be adjustable by hand, but the click did nothing");
                helper.assertTrue(slotAt(helper, pos).delay == setting,
                        type + " should now be on setting " + setting
                                + ", got " + slotAt(helper, pos).delay);
            }

            InnerSwitches.toggle(level, pos);
            helper.assertTrue(slotAt(helper, pos).delay == 1,
                    type + " must wrap from the fourth setting back to the first");
        }

        helper.succeed();
    }

    /**
     * A placed retaining repeater drives the block in front hard, so that block powers what touches it.
     *
     * <p>That is the ordinary "repeater into a solid block, lamp or wire on the far side (or under it)"
     * layout, and it is the one thing a repeater can do that a torch cannot: the block in front is
     * strongly powered, not merely fed a weak signal.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void retainingRepeaterStronglyPowersTheBlockInFront(GameTestHelper helper) {
        // y=1 is the floor; the lamp under the driven block is the "wire below it did not light" case.
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_LAMP.defaultBlockState());
        setBlock(helper, 2, 2, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 3, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 3, 2, 1, Blocks.REDSTONE_WIRE.defaultBlockState());
        // Vanilla's FACING is the side a diode READS from, so this repeater reads from the west - where
        // the redstone block is - and drives the east.
        setBlock(helper, 1, 2, 1, RCRegistry.RETAINING_REPEATER.defaultBlockState()
                .setValue(DiodeBlock.FACING, Direction.WEST));
        setBlock(helper, 0, 2, 1, Blocks.REDSTONE_BLOCK.defaultBlockState());

        afterTicks(helper, 8, () -> helper.succeedWhen(() -> {
            helper.assertTrue(helper.getBlockState(new BlockPos(1, 2, 1)).getValue(DiodeBlock.POWERED),
                    "precondition: the repeater must have switched on");
            helper.assertTrue(lampLit(helper, 2, 1, 1),
                    "the block it drives must power the lamp under it");
            helper.assertTrue(wirePower(helper, 3, 2, 1) == 15,
                    "and the wire on the far side of that block, got " + wirePower(helper, 3, 2, 1));
        }));
    }

    /**
     * A retaining repeater inside a block can read from above and drive the dust below it.
     *
     * <p>Vertical components are the ones with no vanilla blockstate to lean on, so the direction is
     * ours to get right - and this one passes the strength on, which is what the part is for.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void verticalRetainingRepeaterDrivesTheDustBelow(GameTestHelper helper) {
        setBlock(helper, 1, 2, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 1, 0, 1, Blocks.STONE.defaultBlockState());

        placeDust(helper, at(helper, 1, 2, 1), 9);
        // Reading from UP means driving DOWN.
        placeComponent(helper, at(helper, 1, 1, 1), ComponentType.RETAINING_REPEATER, 0, Direction.UP);
        Slot below = placeDust(helper, at(helper, 1, 0, 1), 0);

        tick(helper);

        afterTicks(helper, 8, () -> helper.succeedWhen(() -> helper.assertTrue(below.power == 9,
                "nine going in from above must come out below as nine, got " + below.power)));
    }

    /**
     * The rotation rod walks one fixed cycle: north, east, south, west (clockwise from above), then up, then down,
     * then north again. Six clicks come back to where the component started, whatever face the player clicked.
     *
     * <p>Inner dust is refused - it has no direction to turn - which is also what the tooltip promises.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void rotationRodStepsClockwiseThenUpAndDown(GameTestHelper helper) {
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());

        BlockPos repeater = at(helper, 1, 1, 1);
        Slot slot = placeComponent(helper, repeater, ComponentType.REPEATER, 0, Direction.NORTH);
        ServerLevel level = helper.getLevel();

        // One step per click, in the order the rod promises. The last one wraps, so the cycle is six long.
        Direction[] cycle = {Direction.EAST, Direction.SOUTH, Direction.WEST,
                Direction.UP, Direction.DOWN, Direction.NORTH};
        for (Direction expected : cycle) {
            helper.assertTrue(RotationRod.turn(level, repeater) == RotationRod.Result.TURNED,
                    "every click on a component with a direction must turn it");
            helper.assertTrue(slot.facing == expected,
                    "the cycle must reach " + expected + ", got " + slot.facing);
        }

        // And it really is a cycle: two more clicks land where the first two of the round did.
        helper.assertTrue(RotationRod.turn(level, repeater) == RotationRod.Result.TURNED, "still turning");
        helper.assertTrue(slot.facing == Direction.EAST, "a second round starts over, got " + slot.facing);

        BlockPos dust = at(helper, 2, 1, 1);
        placeDust(helper, dust, 0);
        Direction dustFacing = slotAt(helper, dust).facing;
        helper.assertTrue(RotationRod.turn(level, dust) == RotationRod.Result.NO_DIRECTION,
                "inner dust has no direction, so the rod must decline");
        helper.assertTrue(slotAt(helper, dust).facing == dustFacing, "and leave the dust alone");

        helper.succeed();
    }

    /**
     * <b>A vanilla wire and the host block beside it, each reading the other, must reach a fixed point and
     * stay there.</b>
     *
     * <p>{@link #vanillaWireFollowsInnerPower} and {@link #vanillaWireFeedsInnerRedstone} each hold the pair
     * with a redstone block, so neither ever leaves the two free to re-derive from each other. This one
     * does: the host's inner redstone is the wire's only remaining supply, and the wire is the host's.
     *
     * <p><b>What is asserted.</b> The <b>values</b> this pair settles on depend on vanilla's own
     * scheduled-tick behaviour for a wire with no source, and pinning a number here would be pinning that
     * rather than the fix. What the fix is responsible for is the <b>churn</b>, so that is what is asserted:
     *
     * <ol>
     *   <li><b>Solving it again changes nothing.</b> The values read before and after a forced solve are
     *       identical - which is what "at a fixed point" means, and what a pair feeding itself cannot
     *       do.</li>
     *   <li><b>One solve drains its own queue.</b> {@code SolverLog#roundsInLastTick} is the production
     *       counter behind the {@code round(s)/solve} figure in the log; a pair feeding itself sits at the
     *       budget ({@code MAX_ROUNDS_PER_TICK}) every tick, and a settled one is well below it.</li>
     * </ol>
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aWorldWireAndItsHostBlockSettleInsteadOfSpinning(GameTestHelper helper) {
        // The host, the stone under it, and the wire beside it - the same shape the other two wire tests
        // use, so a difference between them is a difference in what is being asserted and not in the rig.
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 0, 1, Blocks.STONE.defaultBlockState());
        setBlock(helper, 2, 1, 1, Blocks.REDSTONE_WIRE.defaultBlockState());

        BlockPos host = at(helper, 1, 1, 1);
        // THE INNER REDSTONE IS NOT A SOURCE. `placeDust(helper, host, 15)` would mark it as an injected
        // supply, and an injected supply holds the component up from outside: the host would sit at 15 for
        // ever and the wire against it would be reading a value nothing in the world produced. A plain
        // component is what makes the two halves each other's input, which is the shape this test is
        // about.
        Slot slot = new Slot(ComponentType.DUST);
        InnerRedstoneNode node = InnerRedstoneStore.get(helper.getLevel()).getOrCreate(host);
        node.setSlot(slot);
        InnerRedstoneStore.get(helper.getLevel()).markDirty();

        afterTicks(helper, 4, () -> {
            // A solve in the same tick is the strongest form of the claim: whatever the pair settles on,
            // one more solve of it must change nothing at all.
            solve(helper, host);
            int wire = wirePower(helper, 2, 1, 1);
            int inner = powerAt(helper, host);
            int firstRounds = com.jiangyuefengyu.redstonecircuit.logic.SolverLog.roundsInLastTick();
            solve(helper, host);
            int secondRounds = com.jiangyuefengyu.redstonecircuit.logic.SolverLog.roundsInLastTick();

            RCConfig.LOGGER.info("[redstonecircuit] carrier-free wire/host pair settled at wire={} "
                    + "inner={} (rounds: first={} second={})", wire, inner, firstRounds, secondRounds);

            helper.assertTrue(wirePower(helper, 2, 1, 1) == wire,
                    "re-solving must not move the wire: " + wire + " -> " + wirePower(helper, 2, 1, 1));
            helper.assertTrue(powerAt(helper, host) == inner,
                    "nor the inner power: " + inner + " -> " + powerAt(helper, host));
            // THE CLAIM IS STRUCTURAL, NOT A SMALL NUMBER.
            //
            // A settled pair costs a FEW rounds, and "few" is not a constant: the count is how many times
            // the queue refilled while this solve ran, which depends on how the world's own wire ticks
            // interleave with it - and the pair is a ring whose gain is exactly one, so every value from 0
            // to 15 is a fixed point and the path to it is not unique. The iteration order is not the
            // cause: `InnerRedstoneNetwork#settle` drains a hash-keyed dirty set, and sorting it
            // deterministically does not remove the spread.
            //
            // So the assertion states the thing that is actually binary: the queue drained BY ITSELF
            // rather than being stopped by the budget. A pair feeding itself sits at
            // `MAX_ROUNDS_PER_TICK` every tick, and this is one comparison below that - which also stops
            // the number drifting out of the test the day the budget changes.
            helper.assertTrue(secondRounds < com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork
                            .MAX_ROUNDS_PER_TICK,
                    "one solve of a settled pair must drain its own queue, not be stopped by the budget ("
                            + com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork
                                    .MAX_ROUNDS_PER_TICK
                            + " rounds, which a pair feeding itself hits every tick); this settle took "
                            + secondRounds + " round(s)");

            helper.succeed();
        });
    }

    /**
     * <b>A component can be taken out of a position without being dropped, and the store must agree.</b>
     *
     * <p>Two halves of one rule, and both are asserted because they pull in opposite directions:
     * {@code InnerRedstoneInteraction#forget} is what a <b>cut capture</b> uses - everything the block
     * carried is in the payload by then, so dropping it as well would duplicate it - while {@code cleanup}
     * is what <b>breaking a host</b> uses. A build that only ever dropped would duplicate; a build that
     * only ever forgot would destroy the components on every break. The third case is a removal that does
     * neither, which leaves the client holding a component the server no longer has.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void forgettingAComponentDoesNotDropIt(GameTestHelper helper) {
        BlockPos host = at(helper, 1, 1, 1);
        setBlock(helper, 1, 1, 1, Blocks.STONE.defaultBlockState());
        placeDust(helper, host, 9);

        InnerRedstoneNode forgotten = InnerRedstoneInteraction.forget(helper.getLevel(), host);

        helper.assertTrue(forgotten != null && !forgotten.isEmpty(), "the component was there to be taken");
        helper.assertTrue(InnerRedstoneStore.get(helper.getLevel()).get(host) == null,
                "and the store no longer has it - the half a cut capture was missing, which would leave a "
                        + "component the client could see inside whatever block is put there next");
        // Nothing may be dropped either: whatever took it (a cut capture) has it in its payload already.
        helper.assertItemEntityNotPresent(Items.REDSTONE, new BlockPos(1, 1, 1), 4.0);

        placeDust(helper, host, 9);
        InnerRedstoneInteraction.cleanup(helper.getLevel(), host);
        helper.assertItemEntityPresent(Items.REDSTONE, new BlockPos(1, 1, 1), 4.0);
        helper.succeed();
    }
}

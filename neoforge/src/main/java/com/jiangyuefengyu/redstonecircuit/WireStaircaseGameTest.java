package com.jiangyuefengyu.redstonecircuit;

import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Compares the shape a wire of each kind ends up with in the same terrain, side by side.
 *
 * <h2>What this is about</h2>
 * A redstone wire's four connection properties ({@code north/east/south/west}, each
 * {@code none|side|up}) are what the blockstate JSON turns into a picture. Vanilla draws a vertical
 * strip up the face of a step ({@code up}) and a horizontal arm reaching down off one ({@code side});
 * the superconductor inherits vanilla's state definition, so it is supposed to end up with exactly the
 * same four values in exactly the same terrain. Every scenario here is built twice - once in
 * {@link Blocks#REDSTONE_WIRE} as the reference, once in
 * {@link RCRegistry#SUPERCONDUCTING_WIRE} - and each wire of the superconductor's copy is asserted to
 * carry exactly the properties vanilla's does.
 *
 * <h2>Placement, not hand-written states</h2>
 * Every state here is worked out by vanilla's own placement path: {@link Block#getStateForPlacement}
 * with the same {@link BlockPlaceContext} a player's item use produces. The reading is taken a few
 * ticks later, because a placement is not the last word on a wire's shape - the updates it sends run
 * over the rest of the tick, and a state read too early is a state no player ever sees.
 *
 * <h2>Why the two wires live in different columns</h2>
 * One wire's shape depends on the terrain around <em>it</em>, so the two runs must not be each other's
 * neighbours: they are laid out along Z with a column of air between them - the same terrain, with no
 * interaction between the two runs.
 *
 * <h2>The step, and what the two ends are called</h2>
 * <pre>
 *   high wire                       low wire   -- east=up     (strip up the face of the step)
 *      |                            high wire  -- west=side   (arm reaching down off it)
 *   [step block]
 *   low wire  (on the ground)
 * </pre>
 * The two ends are decided by different branches of vanilla's connection rule, so both are checked: a
 * step that only drew one of them would not be joined. The build order is the one a player uses -
 * ground wire first, then the step, then the wire on top - and it matters, because it is what forces
 * the lower wire's connection to be worked out <em>again</em> after the step appears. That is the case
 * the author reported, and the one that used to come out unconnected on the superconductor.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public class WireStaircaseGameTest {

    /** The step's foot: wire lies at this level, its floor is the block below. */
    private static final int LOW_Y = 1;
    /** The step's head: the upper wire lies at this level, on top of the step. */
    private static final int HIGH_Y = 2;

    /** The vanilla reference run, and the superconductor run: identical terrain, a column apart. */
    private static final int VANILLA_Z = 1;
    private static final int SUPER_Z = 3;

    /** How long the world is given to finish what a placement set in motion. */
    private static final int SETTLE_TICKS = 3;

    private static final Direction[] HORIZONTAL =
            {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /** The four connection properties a player would see, in a fixed order. */
    static String sides(BlockState state) {
        if (!state.hasProperty(RedStoneWireBlock.NORTH)) {
            return state.getBlock().getName().getString() + " (not a wire)";
        }
        StringBuilder text = new StringBuilder();
        for (Direction direction : HORIZONTAL) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(direction.name().toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(state.getValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction))
                            .getSerializedName());
        }
        return text.toString();
    }

    /** The strength a wire currently holds, so a shape comparison can also say whether it was fed. */
    static int power(BlockState state) {
        return state.hasProperty(BlockStateProperties.POWER)
                ? state.getValue(BlockStateProperties.POWER)
                : -1;
    }

    /**
     * Places a wire the way a player does: through {@code getStateForPlacement}, and into the north
     * half of the cell while looking down, which is the placement that makes vanilla lay dust flat.
     */
    private static void placeAsPlayer(GameTestHelper helper, Block wire, int x, int y, int z) {
        ServerLevel level = helper.getLevel();
        BlockPos clicked = helper.absolutePos(new BlockPos(x, y - 1, z));
        BlockPlaceContext context = new BlockPlaceContext(
                level,
                null,
                InteractionHand.MAIN_HAND,
                ItemStack.EMPTY,
                new BlockHitResult(Vec3.atCenterOf(clicked), Direction.UP, clicked, false));
        BlockState state = wire.getStateForPlacement(context);
        if (state == null) {
            throw new IllegalStateException("no placement state for " + wire + " at " + new BlockPos(x, y, z));
        }
        helper.setBlock(new BlockPos(x, y, z), state);
    }

    /** A solid block a wire may climb on: stone, a redstone conductor with a sturdy top face. */
    private static void stone(GameTestHelper helper, int x, int y, int z) {
        helper.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
    }

    /**
     * A lever, switched on, standing on the block below it.
     *
     * <p>Used to feed a wire from a cell a block could stand in, where a redstone block would do the
     * same job but is a redstone conductor - and a conductor changes the very terrain whose effect on
     * the shape is under test. Placed last, so nothing can overwrite it.
     */
    private static void poweredLever(GameTestHelper helper, int x, int y, int z) {
        helper.setBlock(new BlockPos(x, y, z), Blocks.LEVER.defaultBlockState()
                .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.POWERED, true));
    }

    /**
     * The step, built in the order a player builds it: floor, wire on the ground, the solid block
     * beside it, then the wire on top of that block.
     *
     * <p>The lower wire is at x=0, the step at x=1 and the upper wire on top of it.
     */
    private static Where placeStep(GameTestHelper helper, int z, Block wire) {
        stone(helper, 0, LOW_Y - 1, z);
        stone(helper, 1, LOW_Y - 1, z);
        // The ground wire before the step: that order is what makes the lower wire decide its
        // connections twice, and the second decision is the one that used to come out unconnected.
        placeAsPlayer(helper, wire, 0, LOW_Y, z);
        stone(helper, 1, LOW_Y, z);
        placeAsPlayer(helper, wire, 1, HIGH_Y, z);
        return new Where(new BlockPos(0, LOW_Y, z), new BlockPos(1, HIGH_Y, z),
                new BlockPos(1, LOW_Y, z));
    }

    /**
     * The same step, with the lower wire fed: a lever placed in the run after the wire, so it powers it
     * without putting a redstone conductor into any cell that decides the step's shape.
     *
     * <p>Returns the lower wire, the upper wire on the step, and the lever feeding the lower one.
     */
    private static Where placeFedStep(GameTestHelper helper, int z, Block wire) {
        for (int x = 0; x <= 3; x++) {
            stone(helper, x, LOW_Y - 1, z);
        }
        placeAsPlayer(helper, wire, 0, LOW_Y, z);
        placeAsPlayer(helper, wire, 2, LOW_Y, z);
        poweredLever(helper, 3, LOW_Y, z);
        stone(helper, 1, LOW_Y, z);
        placeAsPlayer(helper, wire, 1, HIGH_Y, z);
        return new Where(new BlockPos(0, LOW_Y, z), new BlockPos(1, HIGH_Y, z),
                new BlockPos(3, LOW_Y, z));
    }

    /** One terrain layout, built once per wire. */
    private interface Scenario {
        Where run(GameTestHelper helper, int z, Block wire);
    }

    /** The wire cells a scenario wants compared, in structure-relative coordinates. */
    private record Where(BlockPos lower, BlockPos upper, BlockPos source) {
    }

    /** The two wires being compared once the world has settled, plus the cell feeding them. */
    private record Measurement(BlockState lower, BlockState upper, BlockPos source) {
    }

    /**
     * Builds {@code build} once per wire, then reads both back after {@link #SETTLE_TICKS} ticks.
     *
     * <p>The two runs are byte-for-byte the same build: same blocks in the same order, same placement
     * call. The only thing that differs between them is the block being placed, so any difference in
     * the result is a difference in the game rather than in the test.
     */
    private static void buildAndCompare(GameTestHelper helper, Scenario build, Compare check) {
        Where[] where = new Where[2];
        for (int variant = 0; variant < 2; variant++) {
            boolean isSuper = variant == 1;
            int z = isSuper ? SUPER_Z : VANILLA_Z;
            Block wire = isSuper ? RCRegistry.SUPERCONDUCTING_WIRE.get() : Blocks.REDSTONE_WIRE;
            where[variant] = build.run(helper, z, wire);
        }

        helper.runAfterDelay(SETTLE_TICKS, () -> {
            Measurement[] both = new Measurement[2];
            for (int variant = 0; variant < 2; variant++) {
                both[variant] = new Measurement(helper.getBlockState(where[variant].lower()),
                        helper.getBlockState(where[variant].upper()), where[variant].source());
            }
            RCConfig.LOGGER.info("[redstonecircuit] wire staircase {} | vanilla lower[{}] upper[{}] "
                            + "power {}/{} | super lower[{}] upper[{}] power {}/{}",
                    check.name(), sides(both[0].lower()), sides(both[0].upper()),
                    power(both[0].lower()), power(both[0].upper()),
                    sides(both[1].lower()), sides(both[1].upper()),
                    power(both[1].lower()), power(both[1].upper()));
            check.run(helper, both);
        });
    }

    /** What a scenario is called and what it asserts once both runs have settled. */
    private interface Compare {
        String name();

        void run(GameTestHelper helper, Measurement[] both);
    }

    /**
     * Asserts that the superconductor ended up with exactly the same connection properties vanilla did
     * for the same wire of the same scenario, and names both when it did not.
     */
    private static void assertSame(GameTestHelper helper, String what, Measurement[] both, boolean lower) {
        String expected = sides(lower ? both[0].lower() : both[0].upper());
        String actual = sides(lower ? both[1].lower() : both[1].upper());
        helper.assertTrue(expected.equals(actual),
                what + " should be [" + expected + "] as vanilla has it, but the superconductor is ["
                        + actual + "]");
    }

    /**
     * The step, <b>from the foot</b>: the lower wire's side towards the step must be {@code up}.
     *
     * <p>The first of the two ends the author reported, and the one that reddens without the fix: the
     * superconductor used to answer vanilla's "do you connect here?" with no, and kept its
     * placement-time arm instead of growing the rising bar.
     */
    @GameTest(template = "empty")
    public void theLowerWireClimbsTheStepLikeVanilla(GameTestHelper helper) {
        buildAndCompare(helper, (h, z, wire) -> placeStep(h, z, wire), new Compare() {
            @Override
            public String name() {
                return "up-step  ";
            }

            @Override
            public void run(GameTestHelper helper, Measurement[] both) {
                helper.assertTrue(sides(both[0].lower()).contains("east=up"),
                        "precondition: vanilla climbs the step, so its lower wire should read east=up, got ["
                                + sides(both[0].lower()) + "]");
                assertSame(helper, "the lower wire's side towards the step", both, true);
                helper.succeed();
            }
        });
    }

    /**
     * The same step, <b>from the head</b>: the upper wire's side back down it must be {@code side}.
     *
     * <p>The other half of the author's report - a step is only joined if both ends agree - and decided
     * by a different branch of vanilla's rule than the one above.
     */
    @GameTest(template = "empty")
    public void theUpperWireReachesDownTheStepLikeVanilla(GameTestHelper helper) {
        buildAndCompare(helper, (h, z, wire) -> placeStep(h, z, wire), new Compare() {
            @Override
            public String name() {
                return "down-step";
            }

            @Override
            public void run(GameTestHelper helper, Measurement[] both) {
                assertSame(helper, "the upper wire's side back down the step", both, false);
                assertSame(helper, "the lower wire's side towards the step", both, true);
                helper.succeed();
            }
        });
    }

    /**
     * The control: a straight run at one level, so the wires have ordinary neighbours on both sides and
     * the comparison is about plain side connections rather than about steps.
     */
    @GameTest(template = "empty")
    public void flatRunsAgreeWithVanilla(GameTestHelper helper) {
        buildAndCompare(helper, (h, z, wire) -> {
            for (int x = 0; x <= 3; x++) {
                stone(h, x, LOW_Y - 1, z);
            }
            h.setBlock(new BlockPos(3, LOW_Y, z), Blocks.REDSTONE_BLOCK.defaultBlockState());
            placeAsPlayer(h, wire, 0, LOW_Y, z);
            placeAsPlayer(h, wire, 1, LOW_Y, z);
            return new Where(new BlockPos(0, LOW_Y, z), new BlockPos(1, LOW_Y, z),
                    new BlockPos(3, LOW_Y, z));
        }, new Compare() {
            @Override
            public String name() {
                return "flat-run ";
            }

            @Override
            public void run(GameTestHelper helper, Measurement[] both) {
                assertSame(helper, "the first wire", both, true);
                assertSame(helper, "the second wire", both, false);
                helper.succeed();
            }
        });
    }

    /**
     * The shape fix must not change what the wires are worth - the regression guard for it.
     *
     * <p>A shape and a strength are different questions, and the first is not allowed to move the second.
     * The run below the step is fed by a lever, and the two wires' strengths are compared between the
     * two kinds <em>and</em> pinned to the concrete numbers each kind is supposed to produce, so a change
     * that moved both together would still fail.
     *
     * <p>The numbers are not the same for the two kinds, and are not meant to be: that is the whole
     * point of the material. Ordinary dust loses a step for every hop it makes, so it reaches 13 on the
     * wire below the step and 14 on the wire up it (the step costs it one); the superconductor loses
     * nothing, so both of its wires sit at the lever's 15. Vanilla's own strengths are pinned too, so a
     * fix that changed ordinary redstone would also fail here.
     */
    @GameTest(template = "empty")
    public void theStepDoesNotChangeWhatTheWiresAreWorth(GameTestHelper helper) {
        buildAndCompare(helper, (h, z, wire) -> placeFedStep(h, z, wire), new Compare() {
            @Override
            public String name() {
                return "step-powr";
            }

            @Override
            public void run(GameTestHelper helper, Measurement[] both) {
                assertSame(helper, "the lower wire's side towards the step", both, true);
                assertSame(helper, "the upper wire's side back down the step", both, false);

                int vanillaLower = power(both[0].lower());
                int vanillaUpper = power(both[0].upper());
                helper.assertTrue(vanillaLower == 13,
                        "precondition: ordinary dust below the fed step should be 15 - 2, got " + vanillaLower);
                helper.assertTrue(vanillaUpper == 14,
                        "precondition: ordinary dust on the step should be 15 - 1, got " + vanillaUpper);
                helper.assertTrue(power(both[1].lower()) == 15,
                        "the superconductor below the fed step loses nothing, so it should be 15, got "
                                + power(both[1].lower()));
                helper.assertTrue(power(both[1].upper()) == 15,
                        "the superconductor on the step loses nothing either, so it should be 15, got "
                                + power(both[1].upper()));
                helper.succeed();
            }
        });
    }
}

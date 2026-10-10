package com.jiangyuefengyu.redstonecircuit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The outward half, as far as a GameTest can see it: a cell inside the plot carries a signal, the product block answers
 * the redstone query for the face the table names, vanilla's own reader next to it reacts, and the answer goes away the
 * moment the cell is quiet again.
 *
 * <p>The product's own tick cannot run in this harness ({@code StructureBlockEntity.serverTick} needs
 * {@code redstonecircuit:structure_space}, and a GameTest world cannot host a datapack dimension, see
 * {@code StructureInjectionGameTest}). So the plot is claimed in the test level and the evaluation is driven by hand
 * through {@code StructureBlockEntity#applyOutput} - which is the same call the tick makes. Everything below it is real:
 * the cell is read out of the plot, {@code StructureOutput} decides, and {@code updateNeighborsAt} is what makes the lamp
 * and the wire react.
 *
 * <p>The two watched cells are the two halves of "this cell has a signal": a lever <b>standing in</b> the cell (read from
 * the cell's own block) and the cell <b>beside</b> that lever (read from what arrives at it). A rule that asked only one of
 * the two would be a structure that ignores a lever placed on, or pointed at, the cell a player chose.
 */
public final class StructureOutputGameTest implements FabricGameTest {

    /** What the east face answers. Neither 15 nor 1, so a wrong answer would be obvious. */
    private static final int EAST_STRENGTH = 9;
    /** What the up face answers, from the other row and the other way of reading a cell. */
    private static final int UP_STRENGTH = 5;
    private static final int STRONGER = 12;

    /** The cell the east row watches: a lever stands in it. */
    private static final BlockPos LEVER_CELL = new BlockPos(0, 0, 0);
    /** The cell the up row watches: the lever beside it stands in this one's neighbour. */
    private static final BlockPos FED_CELL = new BlockPos(1, 0, 0);

    /** The arrangement: the product, its plot, and the plot's own lever. */
    private record Fixture(StructureBlockEntity product, BlockPos owner, BlockPos origin, BlockPos lever,
                           BlockPos fed) {
    }

    /** One row on each of two faces, each watching one of the two cells. */
    private static List<ActivationTable.OutputEntry> twoRows() {
        return List.of(new ActivationTable.OutputEntry(LEVER_CELL, Direction.EAST, EAST_STRENGTH),
                new ActivationTable.OutputEntry(FED_CELL, Direction.UP, UP_STRENGTH));
    }

    /**
     * Places a product with a plot of its own and a lever in the first watched cell.
     *
     * <p>The owner is a real product block that claims the plot, not a made-up owner position: another test in
     * this suite simulates a restart, and a restore sweeps every plot whose owner does not claim it.
     *
     * <p>The plot's own cells are cleared before they are built, because a plot handed out again after a release keeps
     * the blocks the last test left in it - reading a leftover here would read the previous test's blocks instead of this
     * one's.
     */
    private static Fixture place(GameTestHelper helper, ServerLevel level, List<ActivationTable.OutputEntry> rows) {
        BlockPos owner = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(owner, RCRegistry.STRUCTURE.defaultBlockState(), 3);
        if (!(level.getBlockEntity(owner) instanceof StructureBlockEntity product)) {
            helper.fail("the placed product block must have a block entity");
            return null;
        }
        product.setStructure(UUID.randomUUID(), "output test", new Vec3i(4, 4, 4), IoMode.ACTIVATION_FACES, 0,
                Map.of(), List.of(), rows, null);
        PocketSpace.Plotted claimed = PocketSpace.of(level).claim(4, false, level.dimension(), owner);
        if (!claimed.allowed()) {
            helper.fail("the budget refused a plot, so nothing here could be tested: " + claimed.refusal());
            return null;
        }
        product.setPlotIndex(claimed.plot().index());
        BlockPos origin = PocketSpace.originOf(claimed.plot().index());
        for (int x = -1; x <= 4; x++) {
            for (int y = -1; y <= 4; y++) {
                for (int z = -1; z <= 4; z++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        BlockPos lever = origin.offset(LEVER_CELL);
        level.setBlock(lever.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(lever, Blocks.LEVER.defaultBlockState().setValue(BlockStateProperties.ATTACH_FACE,
                AttachFace.FLOOR), 3);
        return new Fixture(product, owner, origin, lever, origin.offset(FED_CELL));
    }

    private static void throwLever(ServerLevel level, BlockPos lever, boolean on) {
        level.setBlock(lever, level.getBlockState(lever).setValue(BlockStateProperties.POWERED, on), 3);
    }

    /**
     * What a block standing on {@code face} is told - the query vanilla's own reader makes.
     *
     * <p>{@code getOpposite()} is not a workaround: {@code getBestNeighborSignal} asks the neighbour at
     * {@code pos.relative(direction)} with {@code getSignal(neighbour, direction)}, so a reader on the east side asks
     * with {@code WEST}. Probing with {@code getSignal(pos, EAST)} would read the answer this block gives to a reader on
     * its <em>west</em> side, which is the mirror image.
     */
    private static int signalOn(ServerLevel level, BlockPos pos, Direction face) {
        return level.getSignal(pos, face.getOpposite());
    }

    private static boolean lit(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).getValue(BlockStateProperties.LIT);
    }

    private static int dustPower(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(RedStoneWireBlock.POWER) : -1;
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aStructureAnswersOnTheFaceItsTableNames(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fixture set = place(helper, level, twoRows());
        if (set == null) {
            return;
        }
        BlockPos lamp = set.owner().east();
        level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        helper.startSequence()
                .thenExecute(() -> {
                    // (a) The lever is down, so both watched cells are quiet. Nothing is answered, and the world is
                    // not even told - the negative control for "the face answers while the cell has a signal".
                    helper.assertTrue(set.product().applyOutput(level, set.origin()) == 0,
                            "a quiet cell is not a change, and a face that was already silent is not news");
                    helper.assertTrue(signalOn(level, set.owner(), Direction.EAST) == 0,
                            "a silent cell must not make the east face answer, got "
                                    + signalOn(level, set.owner(), Direction.EAST));
                    helper.assertTrue(!level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                            "and the lamp on that face is dark");
                })
                .thenExecute(() -> {
                    // (b) The lever goes up: both rows fire, each on its own face, each at its own strength.
                    throwLever(level, set.lever(), true);
                    helper.assertTrue(set.product().applyOutput(level, set.origin()) == 2,
                            "two faces changed, so two faces have to be notified");
                    helper.assertTrue(signalOn(level, set.owner(), Direction.EAST) == EAST_STRENGTH,
                            "the east face must answer the row's own strength, got "
                                    + signalOn(level, set.owner(), Direction.EAST));
                    helper.assertTrue(signalOn(level, set.owner(), Direction.UP) == UP_STRENGTH,
                            "the up face answers its own row, from the cell the lever feeds, got "
                                    + signalOn(level, set.owner(), Direction.UP));
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    helper.assertTrue(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                            "vanilla's own lamp on that face is lit");
                    // (c) Nothing inside moved: the six answers are the same, so nobody is re-asked. A structure
                    // that notified the world every tick would make every wire beside it recompute every tick.
                    helper.assertTrue(set.product().applyOutput(level, set.origin()) == 0,
                            "the same six answers are not a change");
                })
                .thenExecute(() -> {
                    // (d) A second row on the same face, and the stronger one wins - the rule the face table
                    // already has, and the reason a face is not identified by one row.
                    set.product().setStructure(UUID.randomUUID(), "output test", new Vec3i(4, 4, 4),
                            IoMode.ACTIVATION_FACES, 0, Map.of(), List.of(), List.of(
                                    new ActivationTable.OutputEntry(LEVER_CELL, Direction.EAST, EAST_STRENGTH),
                                    new ActivationTable.OutputEntry(LEVER_CELL, Direction.EAST, STRONGER),
                                    new ActivationTable.OutputEntry(FED_CELL, Direction.UP, UP_STRENGTH)), null);
                    set.product().applyOutput(level, set.origin());
                    helper.assertTrue(signalOn(level, set.owner(), Direction.EAST) == STRONGER,
                            "the stronger of two rows on one face is the one that stands, got "
                                    + signalOn(level, set.owner(), Direction.EAST));
                    // (e) A strength of 0 is a legal row and means nothing to emit, exactly as a face entry's 0.
                    set.product().setStructure(UUID.randomUUID(), "output test", new Vec3i(4, 4, 4),
                            IoMode.ACTIVATION_FACES, 0, Map.of(), List.of(), List.of(
                                    new ActivationTable.OutputEntry(LEVER_CELL, Direction.EAST, EAST_STRENGTH),
                                    new ActivationTable.OutputEntry(FED_CELL, Direction.UP, 0)), null);
                    set.product().applyOutput(level, set.origin());
                    helper.assertTrue(signalOn(level, set.owner(), Direction.UP) == 0,
                            "a row that says 0 answers 0, got " + signalOn(level, set.owner(), Direction.UP));
                    helper.assertTrue(signalOn(level, set.owner(), Direction.EAST) == EAST_STRENGTH,
                            "and the other face is untouched by it");
                })
                .thenExecute(() -> {
                    // (f) The lever goes down again: the level has to come down with it.
                    throwLever(level, set.lever(), false);
                    set.product().applyOutput(level, set.origin());
                })
                // The lamp goes out through vanilla's own four-tick scheduled tick, and a scheduled tick is only
                // collected for a chunk whose full status has propagated - so this waits for it rather than
                // counting ticks.
                .thenWaitUntil(() -> {
                    helper.assertTrue(signalOn(level, set.owner(), Direction.EAST) == 0,
                            "with the lever down the east face is silent again, got "
                                    + signalOn(level, set.owner(), Direction.EAST));
                    helper.assertTrue(!level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                            "and the lamp goes out");
                })
                .thenExecute(() -> PocketSpace.of(level).release(set.product().plotIndex()))
                .thenSucceed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aWireBesideAStructureTakesItsStrength(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Fixture set = place(helper, level, twoRows());
        if (set == null) {
            return;
        }
        BlockPos wire = set.owner().east();
        BlockPos lamp = wire.east();
        level.setBlock(wire.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(wire, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        level.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        // A control, built out of vanilla blocks only, four blocks up and out of the way: a redstone block, the
        // same wire and the same lamp. Driving a wire from beside it is the same shape of change as ours - a
        // source appears, the wire's power moves, the wire tells its neighbours - so this is what separates "our
        // face did not arrive" from "this harness does not light lamps behind wires". Note that a wire passes
        // power on only to a side it is *connected* on.
        BlockPos controlWire = set.owner().above(4);
        BlockPos controlLamp = controlWire.east();
        level.setBlock(controlWire.below(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(controlWire, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        level.setBlock(controlLamp, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        level.setBlock(controlWire.west(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);

        helper.startSequence()
                .thenExecute(() -> {
                    throwLever(level, set.lever(), true);
                    set.product().applyOutput(level, set.origin());
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    // A wire on the face the table names, and a lamp behind the wire.
                    helper.assertTrue(dustPower(level, wire) == EAST_STRENGTH,
                            "vanilla's wire beside the block has to take the strength the east face answers, got "
                                    + dustPower(level, wire));
                    helper.assertTrue(lit(level, controlLamp),
                            "the control's lamp is lit, so a wire driving a lamp does work in this harness");
                    helper.assertTrue(lit(level, lamp),
                            "and the lamp this wire drives is lit - which needs the wire to be *connected* to "
                                    + "this block, and that is the one thing isSignalSource answers");
                })
                .thenExecute(() -> {
                    throwLever(level, set.lever(), false);
                    set.product().applyOutput(level, set.origin());
                })
                .thenWaitUntil(() -> {
                    helper.assertTrue(dustPower(level, wire) == 0,
                            "with the watched cell quiet the wire is dark again, got " + dustPower(level, wire));
                    helper.assertTrue(!level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                            "and so is the lamp");
                })
                .thenExecute(() -> PocketSpace.of(level).release(set.product().plotIndex()))
                .thenSucceed();
    }
}

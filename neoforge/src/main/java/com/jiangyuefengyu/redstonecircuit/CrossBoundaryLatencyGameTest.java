package com.jiangyuefengyu.redstonecircuit;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>The cross-boundary delay is one tick, half of it at each end of a tick</b>, as far as a test level can see it.
 *
 * <p>The two halves are driven by hand, in the order {@code StructureBlockEntity#serverTick} runs them - the outward
 * half first ({@code applyOutput}), the inward half last ({@code readFaces} then {@code pushInjection}, whose write is
 * {@code PocketSpace#applyInjection}). That order is the contract, and it is pinned on the source by
 * {@code block.CrossBoundaryOrderTest}, because {@code serverTick} itself cannot run here: its first act is
 * {@code PocketDimension.of}, and a GameTest world cannot host a datapack dimension. What this class can pin is
 * everything below that call, and it is the half that is easy to get wrong:
 *
 * <ul>
 *   <li>the input arriving late in tick T is <b>written into the pocket inside T</b> - a synchronous write, no scheduled
 *       tick - so the outward half of T+1 has something to read. That is the "half at the far end";</li>
 *   <li>the outward half of T has <b>already run</b> when that input arrives, so the world is still told 0 at the end of
 *       T. That is the "half at the near end", and it is the whole of the one-tick delay;</li>
 *   <li>the first run of the outward half after the input - on a server, the early half of T+1 - answers, and it answers
 *       <b>once</b>: a second run has nothing to report. The answer is a level, not a pulse.</li>
 * </ul>
 *
 * <p>The delay is not asserted by counting ticks here, because there is no tick of this mod's to count: what is asserted
 * is that neither half needs a level tick of its own between the two, which is exactly "one tick and not two".
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class CrossBoundaryLatencyGameTest {

    /** What the input face pushes into the wire cell. Neither 15 nor 1, so a wrong number is obvious. */
    private static final int PUSHED = 13;

    /** What the outward face answers once the cell it watches is driven. */
    private static final int ANSWER = 9;

    /** The cell the input is injected into: a wire, so vanilla answers for it and powers its neighbour. */
    private static final BlockPos WIRE = BlockPos.ZERO;

    /** The cell the outward row watches: the wire beside the injected one, one below the pushed strength. */
    private static final BlockPos BESIDE = new BlockPos(1, 0, 0);

    /** The face whose signal drives the input table. */
    private static final Direction INPUT_FACE = Direction.EAST;

    /** The face the structure answers on - deliberately not the input's, or the two would be one number. */
    private static final Direction OUTPUT_FACE = Direction.NORTH;

    /** The arrangement: the product, where it stands, where its plot starts, and the plot's index. */
    private record Fixture(StructureBlockEntity product, BlockPos owner, BlockPos origin, int plot) {
    }

    /** The input half's table: the east face, when energized, pushes {@link #PUSHED} into the wire cell. */
    private static Map<Direction, List<ActivationTable.FaceEntry>> inputTable() {
        return Map.of(INPUT_FACE, List.of(new ActivationTable.FaceEntry(WIRE, PUSHED)));
    }

    /** The outward half's rows: the face answers {@link #ANSWER} while the cell beside the wire has a signal. */
    private static List<ActivationTable.OutputEntry> outputRows() {
        return List.of(new ActivationTable.OutputEntry(BESIDE, OUTPUT_FACE, ANSWER));
    }

    /** The six-face vector the input half reads once the external face is energized at full strength. */
    private static int[] energized() {
        int[] signals = new int[Direction.values().length];
        signals[INPUT_FACE.ordinal()] = 15;
        return signals;
    }

    /** The same vector before anything is driving this structure. */
    private static int[] quiet() {
        return new int[Direction.values().length];
    }

    /**
     * Places the product, claims its plot and builds the two wire cells.
     *
     * <p>The product is the plot's real owner, not a made-up position: a restore sweeps every plot whose owner does not
     * claim it, and another test in this suite simulates a restart. It does not tick here - {@code serverTick} needs the
     * pocket dimension - so the two halves below are the only drivers.
     */
    private static Fixture place(GameTestHelper helper, ServerLevel level, PocketSpace space) {
        BlockPos owner = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(owner, RCRegistry.STRUCTURE.get().defaultBlockState(), 3);
        if (!(level.getBlockEntity(owner) instanceof StructureBlockEntity product)) {
            helper.fail("the placed product block must have a block entity");
            return null;
        }
        product.setStructure(UUID.randomUUID(), "cross-boundary test", new Vec3i(4, 4, 4), IoMode.ACTIVATION_FACES, 0,
                inputTable(), List.of(), outputRows(), null);
        PocketSpace.Plotted claimed = space.claim(4, false, level.dimension(), owner);
        if (!claimed.allowed()) {
            helper.fail("the budget refused a 4³ plot, so nothing here could be tested: " + claimed.refusal());
            return null;
        }
        product.setPlotIndex(claimed.plot().index());
        BlockPos origin = PocketSpace.originOf(claimed.plot().index());
        // A plot handed out again keeps the blocks the last test left in it, so its own cells are cleared first.
        for (int x = -1; x <= 4; x++) {
            for (int y = -1; y <= 4; y++) {
                for (int z = -1; z <= 4; z++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        for (BlockPos local : new BlockPos[] {WIRE, BESIDE}) {
            // Dust needs a solid block under it, and vanilla's own wiring is what makes the second cell a signal.
            level.setBlock(origin.offset(local).below(), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(origin.offset(local), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        }
        return new Fixture(product, owner, origin, claimed.plot().index());
    }

    private static int dustPower(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(RedStoneWireBlock.POWER) : -1;
    }

    /**
     * What a block standing on {@code face} is told - the query vanilla's own reader makes ({@code getSignal} at the
     * neighbour with the opposite side), the same probe the outward tests use.
     */
    private static int signalOn(ServerLevel level, BlockPos pos, Direction face) {
        return level.getSignal(pos, face.getOpposite());
    }

    @GameTest(template = "empty")
    public void theAnswerLeavesInTheEarlyHalfOfTheNextTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);
        Fixture set = place(helper, level, space);
        if (set == null) {
            return;
        }

        helper.startSequence()
                .thenExecute(() -> {
                    // Tick T, its early half: the outward half runs before anything inside is driven.
                    helper.assertTrue(set.product().applyOutput(level, set.origin()) == 0,
                            "nothing inside this structure is driven yet, so no face changed its answer");
                    helper.assertTrue(signalOn(level, set.owner(), OUTPUT_FACE) == 0,
                            "and the world is told 0 - the negative control: a quiet cell is not an answer");
                })
                .thenExecute(() -> {
                    // Tick T, its late half: the external face goes high and the pocket is written now.
                    helper.assertTrue(space.applyInjection(set.plot(), inputTable(), energized()) == 1,
                            "the input has to land in the pocket in the tick it arrives: one cell changed, so the "
                                    + "inward half did its work in this tick");
                    helper.assertTrue(dustPower(level, set.origin().offset(BESIDE)) == PUSHED - 1,
                            "and it landed synchronously - vanilla's dust beside the driven cell is already powered in "
                                    + "this same tick, got " + dustPower(level, set.origin().offset(BESIDE)));
                    helper.assertTrue(signalOn(level, set.owner(), OUTPUT_FACE) == 0,
                            "but the outward half of tick T has already run: the answer cannot leave in the tick its "
                                    + "input arrived, which is the half-tick at the near end");
                })
                // One real level tick passes with nothing driving anything, which is also what proves the pocket write
                // needed no tick of its own to become readable.
                .thenIdle(1)
                .thenExecute(() -> {
                    // Tick T+1, its early half - exactly one tick after the input, and the first run since.
                    helper.assertTrue(set.product().applyOutput(level, set.origin()) == 1,
                            "the cell the outward row watches now carries a signal, so exactly one face changed");
                    helper.assertTrue(signalOn(level, set.owner(), OUTPUT_FACE) == ANSWER,
                            "the structure must answer " + ANSWER + " on that face, got "
                                    + signalOn(level, set.owner(), OUTPUT_FACE));
                })
                .thenExecute(() -> {
                    helper.assertTrue(set.product().applyOutput(level, set.origin()) == 0,
                            "an answer that has not moved is not news: the same six answers are not a change");
                    helper.assertTrue(signalOn(level, set.owner(), OUTPUT_FACE) == ANSWER,
                            "and it is still there - a level, not a pulse");
                })
                .thenExecute(() -> space.release(set.plot()))
                .thenSucceed();
    }

    @GameTest(template = "empty")
    public void theInputIsReadableInsideThePlotInTheSameTick(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        PocketSpace space = PocketSpace.of(level);
        Fixture set = place(helper, level, space);
        if (set == null) {
            return;
        }
        BlockPos wire = set.origin().offset(WIRE);
        BlockPos beside = set.origin().offset(BESIDE);

        helper.startSequence()
                .thenExecute(() -> {
                    // The far end of the loop, alone: the write, and the read of it, inside one tick.
                    helper.assertTrue(space.applyInjection(set.plot(), inputTable(), quiet()) == 0,
                            "a quiet face injects nothing - the negative control for this half");
                    helper.assertTrue(dustPower(level, beside) == 0,
                            "and the pocket is still dark, got " + dustPower(level, beside));
                })
                .thenExecute(() -> {
                    helper.assertTrue(space.applyInjection(set.plot(), inputTable(), energized()) == 1,
                            "the face is energized, so the cell it names is written once");
                    helper.assertTrue(level.getSignal(wire, INPUT_FACE) == PUSHED,
                            "the injected cell answers the table's own strength in this same tick, got "
                                    + level.getSignal(wire, INPUT_FACE));
                    helper.assertTrue(dustPower(level, beside) == PUSHED - 1,
                            "and vanilla's dust beside it has reacted already: no scheduled tick stands between the "
                                    + "write and what the outward half of the next tick will read, got "
                                    + dustPower(level, beside));
                })
                .thenExecute(() -> space.release(set.plot()))
                .thenSucceed();
    }
}

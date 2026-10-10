package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.TransmissionGateBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
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
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 传输门 in a real level: the three sentences, the output face, and the orientation.
 *
 * <p>The inputs are the mod's own components (redstone placed inside the blocks around the gate, through
 * {@code InnerRedstoneStore}), which is the only way to feed a face a chosen strength: a vanilla wire would
 * recompute whatever was written into it on the next tick, while an inner dust keeps its injected strength and
 * still answers the level's ordinary signal question.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class TransmissionGateGameTest {

    private static BlockPos at(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static void setBlock(GameTestHelper helper, int x, int y, int z, BlockState state) {
        helper.setBlock(new BlockPos(x, y, z), state);
    }

    /** Places inner dust at an absolute position with a chosen strength, the way {@code /rc place} injects one. */
    private static void placeDust(GameTestHelper helper, BlockPos pos, int power) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);

        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        if (power > 0) {
            slot.injectedPower = power;
        }
        store.getOrCreate(pos).setSlot(slot);
        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneInteraction.notifyNeighbours(level, pos);
    }

    /**
     * One side input lets the main input's strength through - the front's strength, not the side's. Asked from
     * behind the gate (the main output) the block gives 7; asked from in front (its own main input) it gives 0.
     */
    @GameTest(template = "empty")
    public void oneSideInputLetsTheMainStrengthThrough(GameTestHelper helper) {
        BlockPos gate = at(helper, 2, 1, 2);
        setBlock(helper, 2, 1, 2, RCRegistry.TRANSMISSION_GATE.get().defaultBlockState()
                .setValue(TransmissionGateBlock.FACING, Direction.NORTH));
        // The main input is the front (north), holding strength 7.
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 2, 1, 1), 7);
        // One side input - the east face - carries a signal. Its strength is not the one that gets through.
        setBlock(helper, 3, 1, 2, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 2), 15);

        TransmissionGateBlock.announce(helper.getLevel(), gate);
        BlockState state = helper.getLevel().getBlockState(gate);

        helper.assertTrue(state.getValue(TransmissionGateBlock.POWER) == 7,
                "one side input open: the main input's 7 comes out, but the block says "
                        + state.getValue(TransmissionGateBlock.POWER));
        helper.assertTrue(state.getValue(TransmissionGateBlock.LIT), "and it is drawn lit");

        helper.assertTrue(state.getSignal(helper.getLevel(), gate, Direction.NORTH) == 7,
                "asked from behind (the main output), the gate answers 7");
        helper.assertTrue(state.getSignal(helper.getLevel(), gate, Direction.SOUTH) == 0,
                "asked from in front (its own main input), it answers nothing - a gate does not feed its own input");
        helper.assertTrue(state.getSignal(helper.getLevel(), gate, Direction.EAST) == 0,
                "and a side input hears nothing back either");
        helper.succeed();
    }

    /** No side input is silence, even with the main input wide open. */
    @GameTest(template = "empty")
    public void noSideInputIsSilence(GameTestHelper helper) {
        BlockPos gate = at(helper, 2, 1, 2);
        setBlock(helper, 2, 1, 2, RCRegistry.TRANSMISSION_GATE.get().defaultBlockState()
                .setValue(TransmissionGateBlock.FACING, Direction.NORTH));
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 2, 1, 1), 15);

        TransmissionGateBlock.announce(helper.getLevel(), gate);
        BlockState state = helper.getLevel().getBlockState(gate);

        helper.assertTrue(state.getValue(TransmissionGateBlock.POWER) == 0,
                "no side input: nothing comes out, but the block says " + state.getValue(TransmissionGateBlock.POWER));
        helper.assertFalse(state.getValue(TransmissionGateBlock.LIT), "and it is not lit");
        helper.succeed();
    }

    /** Two side inputs is not twice the permission: it is none. */
    @GameTest(template = "empty")
    public void twoSideInputsAreSilence(GameTestHelper helper) {
        BlockPos gate = at(helper, 2, 1, 2);
        setBlock(helper, 2, 1, 2, RCRegistry.TRANSMISSION_GATE.get().defaultBlockState()
                .setValue(TransmissionGateBlock.FACING, Direction.NORTH));
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 2, 1, 1), 15);
        setBlock(helper, 3, 1, 2, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 2), 15);
        setBlock(helper, 1, 1, 2, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 1, 1, 2), 1);

        TransmissionGateBlock.announce(helper.getLevel(), gate);
        BlockState state = helper.getLevel().getBlockState(gate);

        helper.assertTrue(state.getValue(TransmissionGateBlock.POWER) == 0,
                "two side inputs (even a weak one): no output, but the block says "
                        + state.getValue(TransmissionGateBlock.POWER));
        helper.succeed();
    }

    /**
     * The orientation on a facing that is not north: the front is the main input wherever it points, and the
     * output is always the back of it.
     */
    @GameTest(template = "empty")
    public void theOutputIsAlwaysTheBackOfTheMainInput(GameTestHelper helper) {
        BlockPos gate = at(helper, 2, 1, 2);
        setBlock(helper, 2, 1, 2, RCRegistry.TRANSMISSION_GATE.get().defaultBlockState()
                .setValue(TransmissionGateBlock.FACING, Direction.EAST));
        // Main input on the east face; one side input on the north face.
        setBlock(helper, 3, 1, 2, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 3, 1, 2), 5);
        setBlock(helper, 2, 1, 1, Blocks.STONE.defaultBlockState());
        placeDust(helper, at(helper, 2, 1, 1), 15);

        TransmissionGateBlock.announce(helper.getLevel(), gate);
        BlockState state = helper.getLevel().getBlockState(gate);

        helper.assertTrue(state.getValue(TransmissionGateBlock.POWER) == 5,
                "front = east, so the east face's 5 comes out; the block says "
                        + state.getValue(TransmissionGateBlock.POWER));
        helper.assertTrue(state.getSignal(helper.getLevel(), gate, Direction.EAST) == 5,
                "asked from behind (west of the block, travelling east), it answers 5");
        helper.assertTrue(state.getSignal(helper.getLevel(), gate, Direction.WEST) == 0,
                "and asked from the front it answers nothing");
        helper.succeed();
    }
}

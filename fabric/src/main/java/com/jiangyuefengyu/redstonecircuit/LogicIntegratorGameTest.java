package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.LogicIntegratorBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.LogicGate;
import com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks;
import com.jiangyuefengyu.redstonecircuit.logic.NoWeakPowerConduction;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * 逻辑整合器 in a real level: which face answers, who it drives, and what it reads - everything that
 * needs vanilla's own plumbing rather than the gates' arithmetic.
 *
 * <p>{@code getSignal}'s direction parameter points from the asker towards the block being asked, so a
 * block whose front is its output converts it. A wire that is off, an idle target and one of our own
 * components reading 0 are all inputs; air is not.
 */
public final class LogicIntegratorGameTest implements FabricGameTest {

    /** The gate, and the cells its own faces are. */
    private static final BlockPos GATE = new BlockPos(3, 3, 3);

    /** A gate with a facing and two numbers already in it. */
    private static BlockState gate(Direction front, LogicGate.Mode mode, int x, int y) {
        return RCRegistry.LOGIC_INTEGRATOR.defaultBlockState()
                .setValue(LogicIntegratorBlock.FACING, front)
                .setValue(LogicIntegratorBlock.MODE, mode)
                .setValue(LogicIntegratorBlock.X, LogicGate.toState(x))
                .setValue(LogicIntegratorBlock.Y, LogicGate.toState(y));
    }

    /**
     * What a consumer standing on {@code face} of the gate is given, through vanilla's own query: the
     * direction points from the asker towards the block being asked.
     */
    private static int answering(ServerLevel level, BlockPos gate, Direction face) {
        return level.getSignal(gate, face.getOpposite());
    }

    /**
     * Places one block beside the gate and makes sure the gate hears about it: {@code setBlock} notifies
     * on its own, and the explicit {@code updateNeighborsAt} removes the dependence on its flags.
     */
    private static void place(GameTestHelper helper, BlockPos relative, Block block) {
        helper.setBlock(relative, block);
        helper.getLevel().updateNeighborsAt(helper.absolutePos(relative), block);
    }

    /** Places an inner dust with a chosen strength inside a host block, the way {@code InnerRedstoneGameTest} does. */
    private static void placeDust(GameTestHelper helper, BlockPos host, int power) {
        ServerLevel level = helper.getLevel();
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        if (power > 0) {
            slot.injectedPower = power;
        }
        InnerRedstoneNode node = store.getOrCreate(host);
        node.setSlot(slot);
        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, host);
        // A component inside a block changes no block state, so nothing in the world would re-read the host.
        InnerRedstoneInteraction.notifyNeighbours(level, host);
    }

    @GameTest(template = "redstonecircuit:empty")
    public void theFrontFaceAnswersAndNothingElseDoes(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction front = Direction.NORTH;
        helper.setBlock(GATE, gate(front, LogicGate.Mode.AND, 15, 15));
        // Two input faces carry a redstone block each: 15 apiece, which is the X this gate was given.
        place(helper, GATE.east(), Blocks.REDSTONE_BLOCK);
        place(helper, GATE.west(), Blocks.REDSTONE_BLOCK);
        BlockPos gate = helper.absolutePos(GATE);

        helper.assertValueEqual(answering(level, gate, front), 15,
                "与门 with both inputs equal to X answers Y, and the front face is where that answer is read");
        for (Direction side : Direction.values()) {
            if (side != front) {
                helper.assertValueEqual(answering(level, gate, side), 0,
                        "the " + side + " face is an input face: it answers nothing");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void theCountRuleIsTheBlocksToo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction front = Direction.NORTH;
        helper.setBlock(GATE, gate(front, LogicGate.Mode.AND, 15, 15));
        BlockPos gate = helper.absolutePos(GATE);

        place(helper, GATE.east(), Blocks.REDSTONE_BLOCK);
        helper.assertValueEqual(answering(level, gate, front), 0,
                "与门 with one input is 大于或小于, so 不工作 - the gate that is set has to be the gate that is wired");
        place(helper, GATE.west(), Blocks.REDSTONE_BLOCK);
        helper.assertValueEqual(answering(level, gate, front), 15,
                "the second input completes the count and the condition");
        place(helper, GATE.west(), Blocks.AIR);
        helper.assertValueEqual(answering(level, gate, front), 0,
                "and taking it away again stops the gate: the answer is derived, never latched");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aLampInFrontLightsAndGoesDarkAgain(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(GATE, gate(Direction.NORTH, LogicGate.Mode.AND, 15, 15));
        place(helper, GATE.north(), Blocks.REDSTONE_LAMP);
        place(helper, GATE.east(), Blocks.REDSTONE_BLOCK);
        place(helper, GATE.west(), Blocks.REDSTONE_BLOCK);
        BlockPos lamp = helper.absolutePos(GATE.north());

        helper.assertTrue(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                "the lamp in front is lit: the answer left the gate the vanilla way - the block in front is notified, "
                        + "the lamp asks its neighbours, and the gate answers with side.getOpposite()");
        place(helper, GATE.west(), Blocks.AIR);
        // Vanilla's lamp waits four ticks before it goes dark, so this is the assertion that keeps trying.
        helper.succeedWhen(() -> helper.assertFalse(level.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                "and it goes dark again when an input is taken away"));
    }

    @GameTest(template = "redstonecircuit:empty")
    public void theSettingsAreTheScreensAndTheScreenIsTheServer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction front = Direction.NORTH;
        helper.setBlock(GATE, gate(front, LogicGate.Mode.AND, 15, 15));
        place(helper, GATE.east(), Blocks.REDSTONE_BLOCK);
        place(helper, GATE.west(), Blocks.REDSTONE_BLOCK);
        BlockPos gate = helper.absolutePos(GATE);

        helper.assertValueEqual(answering(level, gate, front), 15, "与门 with two inputs equal to X answers Y = 15");
        helper.assertTrue(LogicIntegratorBlock.applySettings(level, gate, LogicGate.Mode.OR, 15, 7),
                "the screen's write path takes the gate and the two strengths");
        helper.assertValueEqual(answering(level, gate, front), 7,
                "或门 answers Y, so the new strength is on the front face at once");
        helper.assertTrue(LogicIntegratorBlock.applySettings(level, gate, LogicGate.Mode.AND, 4, 7),
                "and it takes another edit");
        helper.assertValueEqual(answering(level, gate, front), 0,
                "与门 whose inputs are not X answers 0");
        helper.assertTrue(LogicIntegratorBlock.applySettings(level, gate, LogicGate.Mode.AND, LogicGate.UNSET, 7),
                "a blank X is a setting the screen can send");
        helper.assertValueEqual(answering(level, gate, front), 0, "and a blank X stops the gate, as the screen says");
        helper.assertFalse(LogicIntegratorBlock.applySettings(level, gate, LogicGate.Mode.AND, 16, 7),
                "while a strength that is not a strength is refused rather than clamped into something nobody typed");
        helper.assertValueEqual(answering(level, gate, front), 0, "and the refusal left the block exactly as it was");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void theGateReadsOurOwnInnerRedstone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction front = Direction.NORTH;
        BlockPos east = GATE.east();
        BlockPos west = GATE.west();
        // Y is blank: 同或/异或 answer with X and never read it, so an empty Y must not stop them.
        helper.setBlock(GATE, gate(front, LogicGate.Mode.XOR, 7, LogicGate.UNSET));
        helper.setBlock(east, Blocks.STONE);
        helper.setBlock(west, Blocks.STONE);
        placeDust(helper, helper.absolutePos(east), 3);
        placeDust(helper, helper.absolutePos(west), 5);
        BlockPos gate = helper.absolutePos(GATE);

        helper.assertValueEqual(answering(level, gate, front), 7,
                "异或门: two different inputs answer X, and a component inside a host block is an input with any strength "
                        + "- which is the only way to hand a gate anything but fifteen");

        LogicIntegratorBlock.applySettings(level, gate, LogicGate.Mode.XNOR, 7, LogicGate.UNSET);
        placeDust(helper, helper.absolutePos(west), 3);
        helper.assertValueEqual(answering(level, gate, front), 7, "同或门: two equal inputs answer X = 7");

        LogicIntegratorBlock.applySettings(level, gate, LogicGate.Mode.XOR, 7, LogicGate.UNSET);
        helper.assertValueEqual(answering(level, gate, front), 0, "异或门 with two equal inputs answers 0");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aWoodenPickaxeIsEnough(GameTestHelper helper) {
        BlockState state = RCRegistry.LOGIC_INTEGRATOR.defaultBlockState();
        helper.assertTrue(state.is(BlockTags.MINEABLE_WITH_PICKAXE),
                "a pickaxe has to be the correct tool, or there is no drop and no mining speed to go with it");
        helper.assertFalse(state.is(BlockTags.NEEDS_STONE_TOOL) || state.is(BlockTags.NEEDS_IRON_TOOL)
                        || state.is(BlockTags.NEEDS_DIAMOND_TOOL),
                "and no tier may be asked for: any needs_*_tool tag is what makes a wooden pickaxe the wrong tool");
        helper.assertTrue(new ItemStack(Items.WOODEN_PICKAXE).isCorrectToolForDrops(state),
                "so a wooden pickaxe takes it - the whole of what was asked for");
        helper.assertFalse(new ItemStack(Items.WOODEN_SHOVEL).isCorrectToolForDrops(state),
                "while the wrong kind of tool does not, which is the mineable tag's half of the same sentence");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void aWireThatIsOffIsStillAnInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction front = Direction.NORTH;
        helper.setBlock(GATE, gate(front, LogicGate.Mode.NOT, 10, 7));
        // Dust needs something to sit on, or the next update takes it away again.
        helper.setBlock(GATE.east().below(), Blocks.STONE);
        helper.setBlock(GATE.west().below(), Blocks.STONE);
        place(helper, GATE.east(), Blocks.REDSTONE_WIRE);
        BlockPos gate = helper.absolutePos(GATE);

        helper.assertValueEqual(answering(level, gate, front), 7,
                "a dust with no power on it is a wire attached to that face, so 非门 has its one input and "
                        + "0 != 10 answers Y = 7");

        place(helper, GATE.east(), Blocks.REDSTONE_BLOCK);
        helper.assertValueEqual(answering(level, gate, front), 7,
                "the same face carrying 15 is still one input, and 15 is still not X");

        LogicIntegratorBlock.applySettings(level, gate, LogicGate.Mode.NOT, 15, 7);
        helper.assertValueEqual(answering(level, gate, front), 0, "with X = 15 that one input equals X, so 非门 answers 0");

        LogicIntegratorBlock.applySettings(level, gate, LogicGate.Mode.NOT, 10, 7);
        place(helper, GATE.west(), Blocks.REDSTONE_WIRE);
        helper.assertValueEqual(answering(level, gate, front), 0,
                "two wired faces are two inputs whatever they read, and 非门 allows exactly one: 大于也不工作");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void anIdleEmitterAndOurOwnComponentsCount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction front = Direction.NORTH;
        helper.setBlock(GATE, gate(front, LogicGate.Mode.NOT, 10, 7));
        helper.setBlock(GATE.west(), Blocks.STONE);
        BlockPos gate = helper.absolutePos(GATE);

        place(helper, GATE.above(), Blocks.TARGET);
        helper.assertValueEqual(answering(level, gate, front), 7,
                "an idle target is not called a signal source by vanilla and is reading nothing, but it is wired to that "
                        + "face (CAN_EMIT): 非门 has its one input and 0 != 10 answers Y");

        place(helper, GATE.above(), Blocks.AIR);
        helper.assertValueEqual(answering(level, gate, front), 0,
                "nothing attached is no input at all: 非门 does not work without its one input");

        placeDust(helper, helper.absolutePos(GATE.west()), 0);
        helper.assertValueEqual(answering(level, gate, front), 7,
                "and one of our own components inside a block counts at 0 as much as at 15 - it writes no block state, so "
                        + "only the store can see it");
        helper.succeed();
    }

    /**
     * A gate must not carry power that is not its own: a vanilla dust charges a full cube from outside, and
     * the gate must not re-emit that charge out of its other faces.
     *
     * <p>The answer is given by the block being a {@link NoWeakPowerConduction}, which is the marker the patch
     * on vanilla's own signal query reads - see {@code mixin.SignalGetterMixin}. The test asserts the marker
     * and then the consequence: the face the component is wired to reads nothing.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theGateDoesNotCarryPowerItIsNotEmitting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Direction front = Direction.UP;
        helper.setBlock(GATE, gate(front, LogicGate.Mode.NOT, 15, 15));
        helper.setBlock(GATE.west(), Blocks.STONE);
        BlockPos gate = helper.absolutePos(GATE);
        BlockPos host = helper.absolutePos(GATE.west());
        placeDust(helper, host, 0);
        // The output: a dust on the front face - which is exactly what charges a full cube from the outside.
        place(helper, GATE.above(), Blocks.REDSTONE_WIRE);

        helper.assertValueEqual(answering(level, gate, front), 15,
                "非门 with one attached input reading 0 does not equal X = 15, so the front answers Y = 15");
        helper.assertValueEqual(level.getDirectSignal(helper.absolutePos(GATE.above()), Direction.UP), 15,
                "the dust in front is CHARGING the gate from outside: a vanilla dust answers the strong question with its "
                        + "own signal on every side (RedStoneWireBlock#getDirectSignal)");
        helper.assertTrue(level.getBlockState(gate).getBlock() instanceof NoWeakPowerConduction,
                "and the gate is not a conductor of stone: the one answer that decides whether that charge is carried "
                        + "through it and out of its other faces");
        helper.assertValueEqual(InnerRedstoneNetwork.readSignal(level, gate, Direction.EAST), 0,
                "so the face the inner component is wired to reads nothing at all, even while the front face is at 15");
        InnerRedstoneNetwork.tick(level);
        InnerRedstoneNode node = InnerRedstoneStore.get(level).get(host);
        helper.assertTrue(node == null || node.isEmpty() || node.slot().power == 0,
                "and the component beside the gate stays dark after a solve: the gate's own answer must never be handed "
                        + "back to it");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void theIntegratorIsNotAHost(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(GATE, gate(Direction.NORTH, LogicGate.Mode.AND, 15, 15));
        helper.setBlock(GATE.below(), Blocks.STONE);

        helper.assertFalse(HostRules.isValidHost(level, helper.absolutePos(GATE)),
                "no component may be put inside the gate: it would be a second owner of the six faces, and the query it "
                        + "would answer is asked before the block's own getSignal");
        helper.assertTrue(HostRules.isValidHost(level, helper.absolutePos(GATE.below())),
                "while an ordinary full cube beside it is still a host - this is one block being refused, not the rule");
        helper.succeed();
    }

    /**
     * Two gates facing each other input-to-input are not wired to each other: a face that can only ever be
     * an input cannot signal, so it is not a wire.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void twoGatesFacingEachOtherInputToInputAreNotWired(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos east = GATE.east();
        // Same facing: the east gate's output also points north, so the face the two share is an input face on both sides.
        helper.setBlock(GATE, gate(Direction.NORTH, LogicGate.Mode.NOT, 10, 7));
        helper.setBlock(east, gate(Direction.NORTH, LogicGate.Mode.NOT, 10, 7));
        BlockPos gate = helper.absolutePos(GATE);
        BlockPos other = helper.absolutePos(east);

        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(), 0,
                "the east face of this gate holds another gate's INPUT face, which cannot signal, so nothing "
                        + "is wired to it");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, other, level.getBlockState(other)).inputs(), 0,
                "and the same from the other side: the exemption is the face rule, not one gate being special");
        helper.assertValueEqual(answering(level, gate, Direction.NORTH), 0,
                "非门 has no input at all, so it does not work - rather than counting a neighbour that can "
                        + "say nothing and answering Y");
        helper.succeed();
    }

    /**
     * The other half: a gate's output face pointing at our input face is a wire, idle or not. Only
     * {@code FACING} is an emitter, and it is one while its answer is 0.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aGatesOutputFaceIsWiredToOurInputFace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos east = GATE.east();
        // The east gate answers westwards, straight into this gate's east face - an input face, seen from here.
        helper.setBlock(GATE, gate(Direction.NORTH, LogicGate.Mode.NOT, 15, 9));
        helper.setBlock(east, gate(Direction.WEST, LogicGate.Mode.AND, 15, 7));
        BlockPos gate = helper.absolutePos(GATE);
        BlockPos other = helper.absolutePos(east);

        helper.assertValueEqual(answering(level, other, Direction.WEST), 0,
                "the east gate is 与门 with no inputs at all, so its own answer is 0 and it is not working either");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(),
                1 << Direction.EAST.ordinal(),
                "but its OUTPUT face is the face the two share, so that face is wired - one input, whatever "
                        + "it is reading");

        place(helper, east.north(), Blocks.REDSTONE_BLOCK);
        place(helper, east.south(), Blocks.REDSTONE_BLOCK);
        helper.assertValueEqual(answering(level, other, Direction.WEST), 7, "与门 with both inputs at X = 15 answers Y = 7");
        helper.assertValueEqual(
                LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).readings()[Direction.EAST.ordinal()],
                7, "and the answer arrives through the shared face unchanged - the exempted case and the working one are the "
                        + "same line, so this is what the exemption must not have taken away");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(),
                1 << Direction.EAST.ordinal(), "still exactly one input: the other gate is one face, not two");
        helper.assertValueEqual(answering(level, gate, Direction.NORTH), 9,
                "so 非门 with X = 15 receiving 7 answers Y = 9 out of its own front - two gates, end to end");
        helper.succeed();
    }

    /**
     * A repeater that does not point at this face cannot drive it, and neither can a torch or a wire that can never
     * reach it.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void onlyNeighboursThatCanDriveTheFaceCount(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(GATE, gate(Direction.NORTH, LogicGate.Mode.NOT, 10, 7));
        BlockPos gate = helper.absolutePos(GATE);
        // The floor a repeater and a wire need, so that nothing here pops off while the test runs.
        helper.setBlock(GATE.west().below(), Blocks.STONE);
        helper.setBlock(GATE.below().below(), Blocks.STONE);

        // A repeater whose output leaves on the far side: nothing it does can reach this face.
        helper.setBlock(GATE.west(), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.EAST));
        helper.assertTrue(level.getBlockState(helper.absolutePos(GATE.west())).is(Blocks.REPEATER),
                "the repeater has to be standing there for this to be about its face");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(), 0,
                "its output points west, away from the gate, so its east face is not a wire");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).answer().power(), 0,
                "非门 with no input at all does not work");

        // Turned round so that its output points at the gate.
        helper.setBlock(GATE.west(), Blocks.REPEATER.defaultBlockState().setValue(RepeaterBlock.FACING, Direction.WEST));
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(),
                1 << Direction.WEST.ordinal(),
                "with its output on this face it is wired - and it is wired while it is off, as a wire is");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).answer().power(), 7,
                "0 != X = 10, so 非门 answers Y = 7");

        // A wall torch hanging on this face: the one face a torch never drives.
        helper.setBlock(GATE.west(), Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                .setValue(RedstoneWallTorchBlock.FACING, Direction.WEST));
        helper.assertTrue(level.getBlockState(helper.absolutePos(GATE.west())).is(Blocks.REDSTONE_WALL_TORCH),
                "and the torch has to be hanging there, on this block");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(), 0,
                "a torch never signals into the block it is attached to, so one hanging here is no input");

        // A standing torch on the roof: it never drives the block it stands on either.
        helper.setBlock(GATE.west(), Blocks.AIR);
        helper.setBlock(GATE.above(), Blocks.REDSTONE_TORCH);
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(), 0,
                "a standing torch on the roof does not drive the block under it");

        // Dust under the gate: vanilla's own exclusion - a wire never drives the block above it.
        helper.setBlock(GATE.above(), Blocks.AIR);
        helper.setBlock(GATE.below(), Blocks.REDSTONE_WIRE);
        helper.assertTrue(level.getBlockState(helper.absolutePos(GATE.below())).is(Blocks.REDSTONE_WIRE),
                "the wire below has to be standing there too");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(), 0,
                "dust below the gate pushes into the block under it, not into the one above");

        // The same dust moved beside the gate: a wire, off or not - the rule this gate has always had.
        helper.setBlock(GATE.below(), Blocks.AIR);
        helper.setBlock(GATE.west(), Blocks.REDSTONE_WIRE);
        helper.assertTrue(level.getBlockState(helper.absolutePos(GATE.west())).is(Blocks.REDSTONE_WIRE),
                "and the one beside it as well");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(),
                1 << Direction.WEST.ordinal(),
                "while a dust beside it is a wire whether or not it is carrying anything");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).answer().power(), 7,
                "and that one input reading 0 answers Y = 7");
        helper.succeed();
    }

    /**
     * A face the wrench cut cannot drive this one, and clearing the cut puts it back.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void aWrenchCutFaceIsNotAnInput(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos west = GATE.west();
        helper.setBlock(GATE, gate(Direction.NORTH, LogicGate.Mode.NOT, 10, 7));
        helper.setBlock(west, Blocks.STONE);
        BlockPos gate = helper.absolutePos(GATE);
        BlockPos host = helper.absolutePos(west);

        placeDust(helper, host, 0);
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(),
                1 << Direction.WEST.ordinal(),
                "a component stored in the neighbour counts even at 0: no block state changes, so only the store sees it");

        helper.assertTrue(WrenchLinks.link(level, host, gate) == WrenchLinks.Result.LINKED_ON,
                "the wrench's first click locks the line between the stored dust and this gate");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(),
                1 << Direction.WEST.ordinal(),
                "a lock keeps the line it names, so the face is still an input");

        helper.assertTrue(WrenchLinks.link(level, host, gate) == WrenchLinks.Result.LINKED_OFF,
                "the second click cuts it");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(), 0,
                "and a face the wrench cut is no input at all, whatever is stored behind it");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).answer().power(), 0,
                "so 非门 does not work: 大于或小于 is 不工作, not Y");

        helper.assertTrue(WrenchLinks.clear(level, host) == WrenchLinks.Result.CLEARED,
                "clearing the wrench takes the component back to automatic");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).inputs(),
                1 << Direction.WEST.ordinal(),
                "and the face is an input again");
        helper.assertValueEqual(LogicIntegratorBlock.readout(level, gate, level.getBlockState(gate)).answer().power(), 7,
                "its one input still reads 0, which is not X = 10, so the front answers Y = 7 again");
        helper.succeed();
    }
}

package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlock;
import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 方块放置器 in a real level: what one activation does, and - just as importantly - what it must not do.
 *
 * <p>One activation places one block out of the front face, taken from the front of a stack once; the
 * placed block's facing is the output face for both of vanilla's conventions; a taken cell or an empty
 * box is no reaction and spends nothing; the lowest filled slot is the one that empties; a signal that
 * stays on places exactly one block.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class BlockPlacerGameTest {

    /** The machine, and the cell its front face points at. The same layout {@code LogicIntegratorGameTest} uses. */
    private static final BlockPos PLACER = new BlockPos(3, 3, 3);

    /** 正面: the output face, and therefore the cell that receives the block. */
    private static final Direction OUT = Direction.NORTH;

    /** The machine with its front pointing somewhere. */
    private static BlockState placer(Direction front) {
        return RCRegistry.BLOCK_PLACER.get().defaultBlockState().setValue(BlockPlacerBlock.FACING, front);
    }

    /** The box the machine keeps its blocks in. */
    private static BlockPlacerBlockEntity box(GameTestHelper helper) {
        BlockEntity entity = helper.getLevel().getBlockEntity(helper.absolutePos(PLACER));
        helper.assertTrue(entity instanceof BlockPlacerBlockEntity, "the machine has its nine-slot block entity");
        return (BlockPlacerBlockEntity) entity;
    }

    /**
     * Places one block beside the machine and makes sure the machine hears about it: {@code setBlock}
     * notifies on its own, and the explicit {@code updateNeighborsAt} removes the dependence on its flags.
     */
    private static void place(GameTestHelper helper, BlockPos relative, Block block) {
        helper.setBlock(relative, block);
        helper.getLevel().updateNeighborsAt(helper.absolutePos(relative), block);
    }

    /** The machine's own state, from the level. */
    private static BlockState machine(GameTestHelper helper) {
        return helper.getLevel().getBlockState(helper.absolutePos(PLACER));
    }

    /** What is in one of the nine slots right now. */
    private static ItemStack slot(GameTestHelper helper, int index) {
        return box(helper).getItem(index);
    }

    /** 把方块放进第 index 格. */
    private static void stock(GameTestHelper helper, int index, Block block, int count) {
        box(helper).setItem(index, new ItemStack(block, count));
    }

    /** One activation, driven the way the scheduled tick drives it. */
    private static void activate(GameTestHelper helper) {
        BlockPos machine = helper.absolutePos(PLACER);
        BlockPlacerBlock.placeFrom(helper.getLevel(), helper.getLevel().getBlockState(machine), machine);
    }

    /** Waiting for the tick the machine asked for; see {@code InnerRedstoneGameTest#afterTicks}. */
    private static void afterTicks(GameTestHelper helper, int ticks, Runnable action) {
        helper.runAfterDelay(ticks, action);
    }

    // --------------------------------------------------------------- tests --

    @GameTest(template = "empty")
    public void oneActivationPlacesOneBlockOutOfTheFrontFace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(PLACER, placer(OUT));
        stock(helper, 0, Blocks.STONE, 3);
        // What the five faces that are NOT the output face hold right now. Whatever the harness put around the
        // test area is none of this machine's business - and the claim is not "they are air", it is "none of them
        // moved", which is the same claim without a promise about the test rig.
        BlockState[] untouched = new BlockState[Direction.values().length];
        for (Direction side : Direction.values()) {
            untouched[side.ordinal()] = level.getBlockState(helper.absolutePos(PLACER.relative(side)));
        }

        activate(helper);

        helper.assertBlockPresent(Blocks.STONE, PLACER.north());
        helper.assertValueEqual(slot(helper, 0).getCount(), 2,
                "激活一次 redstone signal places exactly one block: the stack goes from three to two");
        for (Direction side : Direction.values()) {
            if (side != OUT) {
                helper.assertValueEqual(level.getBlockState(helper.absolutePos(PLACER.relative(side))),
                        untouched[side.ordinal()],
                        "only the output face places anything, so the cell on its " + side
                                + " side must be exactly what it was before the activation");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void aStairRisesTowardsTheOutputFace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(PLACER, placer(OUT));
        stock(helper, 0, Blocks.OAK_STAIRS, 1);

        activate(helper);

        BlockState landed = level.getBlockState(helper.absolutePos(PLACER.north()));
        helper.assertTrue(landed.is(Blocks.OAK_STAIRS), "the stair went in front of the machine");
        helper.assertValueEqual(landed.getValue(StairBlock.FACING), OUT,
                "a stair takes its facing from the direction the placer is \"looking\", so it "
                        + "rises away from the machine");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void aPistonPushesTowardsTheOutputFace(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(PLACER, placer(OUT));
        stock(helper, 0, Blocks.PISTON, 1);

        activate(helper);

        BlockState landed = level.getBlockState(helper.absolutePos(PLACER.north()));
        helper.assertTrue(landed.is(Blocks.PISTON), "the piston went in front of the machine");
        helper.assertValueEqual(landed.getValue(DirectionalBlock.FACING), OUT,
                "and it points the way the block was placed - out of the machine, not back at it. Vanilla asks two "
                        + "different questions about \"the player's facing\" (one for stairs, one for pistons, which "
                        + "flip it); the context answers both so that the answer is the same: the output direction");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void aSlabLandsAsABottomSlab(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(PLACER, placer(OUT));
        stock(helper, 0, Blocks.STONE_SLAB, 1);

        activate(helper);

        BlockState landed = level.getBlockState(helper.absolutePos(PLACER.north()));
        helper.assertValueEqual(landed.getValue(SlabBlock.TYPE), SlabType.BOTTOM,
                "the synthetic click is the middle of the machine's front face - vanilla's own hit vector - so a "
                        + "slab lands on the bottom half exactly as if a player had clicked the machine from behind");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void anEmptyBoxDoesNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(PLACER, placer(OUT));

        activate(helper);

        helper.assertTrue(level.getBlockState(helper.absolutePos(PLACER.north())).isAir(),
                "an empty box places nothing, and there is nothing to consume");
        helper.assertItemEntityNotPresent(Items.STONE, PLACER, 4.0);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void aTakenCellDoesNothingAndCostsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(PLACER, placer(OUT));
        stock(helper, 0, Blocks.STONE, 2);
        place(helper, PLACER.north(), Blocks.DIRT);

        activate(helper);

        helper.assertTrue(level.getBlockState(helper.absolutePos(PLACER.north())).is(Blocks.DIRT),
                "the cell in front still holds what was there");
        helper.assertValueEqual(slot(helper, 0).getCount(), 2,
                "and the block that could not be placed is still in the box: 无反应 must not eat it");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void theLowestFilledSlotIsTheOneThatEmpties(GameTestHelper helper) {
        helper.setBlock(PLACER, placer(OUT));
        stock(helper, 3, Blocks.STONE, 1);
        stock(helper, 5, Blocks.DIRT, 1);

        activate(helper);

        helper.assertBlockPresent(Blocks.STONE, PLACER.north());
        helper.assertTrue(slot(helper, 3).isEmpty(), "the first filled slot is the one that is spent");
        helper.assertValueEqual(slot(helper, 5).getCount(), 1,
                "and a later slot is untouched: the lowest filled slot is the one that empties, which is the rule"
                        + " that differs from the dispenser this machine borrows its window from (it picks at random)");
        helper.succeed();
    }

    /**
     * <b>The rising edge, through the real tick.</b>
     *
     * <p>Nothing here calls the machine's action by hand: a redstone block is put beside it and the world is
     * left to deliver the neighbour change and the scheduled tick. Then the power stays on for several ticks -
     * which must place <b>nothing</b> more - and is taken away and given again, which must place one more.
     */
    @GameTest(template = "empty")
    public void aSignalThatStaysOnPlacesExactlyOneBlock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos machine = helper.absolutePos(PLACER);
        helper.setBlock(PLACER, placer(OUT));
        stock(helper, 0, Blocks.STONE, 4);
        place(helper, PLACER.east(), Blocks.REDSTONE_BLOCK);

        afterTicks(helper, 4, () -> {
            helper.assertBlockPresent(Blocks.STONE, PLACER.north());
            helper.assertValueEqual(slot(helper, 0).getCount(), 3, "the rising edge placed one block");
            helper.assertTrue(machine(helper).getValue(BlockPlacerBlock.TRIGGERED),
                    "and the latch remembers that this signal has already been spent");
        });
        afterTicks(helper, 12, () -> {
            helper.assertValueEqual(slot(helper, 0).getCount(), 3,
                    "a signal that stays on is one activation, not one per tick");
            place(helper, PLACER.east(), Blocks.AIR);
            helper.setBlock(PLACER.north(), Blocks.AIR);
        });
        afterTicks(helper, 16, () -> {
            helper.assertFalse(machine(helper).getValue(BlockPlacerBlock.TRIGGERED),
                    "taking the signal away releases the latch");
            place(helper, PLACER.east(), Blocks.REDSTONE_BLOCK);
        });
        afterTicks(helper, 24, () -> {
            helper.assertBlockPresent(Blocks.STONE, PLACER.north());
            helper.assertValueEqual(slot(helper, 0).getCount(), 2,
                    "and giving it again is a second activation, which places a second block");
            helper.assertTrue(machine(helper).getValue(BlockPlacerBlock.TRIGGERED),
                    "with the latch closed again");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public void onlyBlocksGoIntoTheBox(GameTestHelper helper) {
        helper.setBlock(PLACER, placer(OUT));
        BlockPlacerBlockEntity placer = box(helper);

        helper.assertTrue(placer.canPlaceItem(0, new ItemStack(Items.STONE)), "a block goes in");
        helper.assertFalse(placer.canPlaceItem(0, new ItemStack(Items.ARROW)),
                "and an arrow does not: this machine has one action, so an item it could never "
                        + "use would be an item the player watches do nothing for ever");
        helper.assertFalse(placer.canPlaceItem(0, ItemStack.EMPTY), "an empty hand is not an item");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void theBoxIsNineSlotsAndAWindow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(PLACER, placer(OUT));

        helper.assertValueEqual(box(helper).getContainerSize(), 9, "nine slots, like the dispenser's");
        helper.assertTrue(level.getBlockEntity(helper.absolutePos(PLACER)) instanceof MenuProvider,
                "and a MenuProvider, which is what lets a player open the dispenser's own window on it");
        helper.assertValueEqual(level.getBlockState(helper.absolutePos(PLACER)).getAnalogOutputSignal(level,
                        helper.absolutePos(PLACER)),
                0, "an empty box reads zero to a comparator, like an empty dispenser");
        helper.succeed();
    }

    /**
     * 破坏时里面的方块掉出来: driven through {@code playerDestroy} - the player's half of a removal - because
     * that is where the machine puts the drop. {@code dropContentsOnDestroy} in {@code onRemove} would also
     * fire when a contraption takes the machine out of the world, spilling the box while its slots travel.
     */
    @GameTest(template = "empty")
    public void theBoxSpillsWhenAPlayerBreaksTheMachine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(PLACER, placer(OUT));
        stock(helper, 4, Blocks.STONE, 1);
        BlockPos machine = helper.absolutePos(PLACER);
        BlockState state = level.getBlockState(machine);

        state.getBlock().playerDestroy(level, helper.makeMockPlayer(GameType.SURVIVAL), machine, state,
                level.getBlockEntity(machine), ItemStack.EMPTY);
        helper.setBlock(PLACER, Blocks.AIR);

        helper.assertItemEntityPresent(Items.STONE, PLACER, 4.0);
        helper.assertItemEntityPresent(RCRegistry.BLOCK_PLACER_ITEM.get(), PLACER, 4.0);
        helper.succeed();
    }

    /**
     * The placement the physical half writes with is the world path's own placement: {@code placeInto} is
     * what the compat half calls with the cell of the structure it has just made, and it needs no Sable.
     */
    @GameTest(template = "empty")
    public void placingIntoACellIsTheSamePlacement(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos free = helper.absolutePos(PLACER.north());
        ItemStack stairs = new ItemStack(Blocks.OAK_STAIRS, 3);

        helper.assertTrue(BlockPlacerBlock.placeInto(level, free, OUT, stairs).consumesAction(),
                "a fresh structure's cell is empty, so the block goes in - through vanilla's own placement");
        helper.assertValueEqual(level.getBlockState(free).getValue(StairBlock.FACING), OUT,
                "with the world path's orientation rule: the block faces the output direction");
        helper.assertValueEqual(stairs.getCount(), 2, "and the item spent exactly once, by vanilla's own placement");

        BlockPos taken = helper.absolutePos(PLACER.east());
        helper.setBlock(PLACER.east(), Blocks.DIRT);
        ItemStack stone = new ItemStack(Blocks.STONE, 2);
        helper.assertFalse(BlockPlacerBlock.placeInto(level, taken, OUT, stone).consumesAction(),
                "a cell that cannot take the block is refused rather than handing the placement to a neighbouring "
                        + "cell - vanilla's own rule for a click on a solid block, which is not what a machine wants");
        helper.assertValueEqual(stone.getCount(), 2, "and the block that could not be placed is not spent");

        BlockPos floating = helper.absolutePos(PLACER.west());
        ItemStack torch = new ItemStack(Blocks.TORCH, 1);
        helper.assertFalse(BlockPlacerBlock.placeInto(level, floating, OUT, torch).consumesAction(),
                "a torch with nothing to stand on cannot be placed in mid-air - by vanilla's own rule, and it is the "
                        + "case the compat half answers by taking the new structure back out again");
        helper.assertTrue(level.getBlockState(floating).isAir(), "nothing was written there");
        helper.assertValueEqual(torch.getCount(), 1, "and nothing was spent");
        helper.succeed();
    }

    /**
     * The carrier's per-tick walk is what arms a machine inside a structure: a plot cell's neighbours are the
     * plot's, so no world signal reaches a neighbour update there. The second half is that polling is safe -
     * asking again while the signal is still on must place nothing more.
     */
    @GameTest(template = "empty")
    public void theCarrierWalkArmsTheMachine(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // The signal first, so that placing the machine does not notify it of anything: it is powered and unaware.
        place(helper, PLACER.east(), Blocks.REDSTONE_BLOCK);
        helper.setBlock(PLACER, placer(OUT));
        stock(helper, 0, Blocks.STONE, 2);
        BlockPos machine = helper.absolutePos(PLACER);
        helper.assertFalse(machine(helper).getValue(BlockPlacerBlock.TRIGGERED),
                "a machine placed beside a signal nobody told it about is not armed");

        BlockPlacerBlock.refreshCarried(level, machine, machine(helper));

        helper.assertTrue(machine(helper).getValue(BlockPlacerBlock.TRIGGERED),
                "the walk arms it: this is the only thing that asks a machine inside a plot about the world it is "
                        + "drawn in");
        afterTicks(helper, 4, () -> {
            helper.assertBlockPresent(Blocks.STONE, PLACER.north());
            helper.assertValueEqual(slot(helper, 0).getCount(), 1,
                    "and the placement tick it asked for does the rest");
            BlockPlacerBlock.refreshCarried(level, machine, machine(helper));
            helper.assertValueEqual(slot(helper, 0).getCount(), 1,
                    "asking again while the signal is still on places nothing more: the walk runs every tick, so it "
                            + "has to be idempotent");
            helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public void aWoodenPickaxeIsEnough(GameTestHelper helper) {
        BlockState state = RCRegistry.BLOCK_PLACER.get().defaultBlockState();
        helper.assertTrue(state.is(BlockTags.MINEABLE_WITH_PICKAXE),
                "a pickaxe has to be the correct tool, or there is no drop and no mining speed to go "
                        + "with it");
        helper.assertFalse(state.is(BlockTags.NEEDS_STONE_TOOL) || state.is(BlockTags.NEEDS_IRON_TOOL)
                        || state.is(BlockTags.NEEDS_DIAMOND_TOOL),
                "and no tier may be asked for: any needs_*_tool tag is what makes a wooden pickaxe the wrong tool");
        helper.assertTrue(new ItemStack(Items.WOODEN_PICKAXE).isCorrectToolForDrops(state),
                "so a wooden pickaxe takes it - the whole of what was asked for");
        helper.assertFalse(new ItemStack(Items.WOODEN_SHOVEL).isCorrectToolForDrops(state),
                "while the wrong kind of tool does not");
        helper.succeed();
    }
}

package com.jiangyuefengyu.redstonecircuit;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The two structure blocks are diamond-pickaxe blocks: the controller and the product it places.
 *
 * <p>Both already asked for the correct tool ({@code requiresCorrectToolForDrops}), but nothing said <em>which</em>
 * tool: a block that is not in a {@code mineable/*} tag has no tool of its own (so no mining speed bonus either), and
 * one that is in no {@code needs_*_tool} tag is satisfied by a wooden one - so in survival it was either slow or free,
 * depending on what a player happened to hold.
 *
 * <p>Two tags do it, and this test asks the block state rather than the files, so it is the game's own answer:
 * {@code mineable/pickaxe} makes a pickaxe the correct tool (and gives it the speed), {@code needs_diamond_tool} is the
 * tier. The drop itself needs no work: a player break only calls {@code Block#playerDestroy} when
 * {@code canHarvestBlock} says the tool was good enough, so the wrong tier takes the block and gets nothing.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class StructureBlockMiningGameTest {

    @GameTest(template = "empty")
    public void theRedstoneStructureBlockIsADiamondPickaxeBlock(GameTestHelper helper) {
        check(helper, "红石结构方块", RCRegistry.REDSTONE_STRUCTURE_BLOCK.get().defaultBlockState());
        helper.succeed();
    }

    @GameTest(template = "empty")
    public void theStructureProductIsADiamondPickaxeBlock(GameTestHelper helper) {
        check(helper, "结构（缩小后的结构本身）", RCRegistry.STRUCTURE.get().defaultBlockState());
        helper.succeed();
    }

    /**
     * What "a diamond pickaxe block" means, asked of the game rather than read out of the files.
     *
     * <p>The three tags are the three halves of the rule - {@code mineable/pickaxe} makes a pickaxe the correct tool,
     * {@code needs_diamond_tool} is the tier, {@code requiresCorrectToolForDrops} makes the tier matter at all - and
     * then the items, because that is what a player actually holds.
     */
    private static void check(GameTestHelper helper, String name, BlockState state) {
        helper.assertTrue(state.is(BlockTags.MINEABLE_WITH_PICKAXE),
                name + " must be mineable with a pickaxe, or a pickaxe is not 'the correct tool' at all and there is no "
                        + "mining speed to go with it");
        helper.assertTrue(state.is(BlockTags.NEEDS_DIAMOND_TOOL),
                name + " must be a diamond-tier block, which is the tag vanilla reads to decide whether a tool is good "
                        + "enough");
        helper.assertTrue(state.requiresCorrectToolForDrops(),
                name + " must still refuse to drop for something that is not the correct tool: the tier decides what "
                        + "counts");
        helper.assertTrue(new ItemStack(Items.DIAMOND_PICKAXE).isCorrectToolForDrops(state),
                "a diamond pickaxe must be able to take " + name);
        helper.assertTrue(!new ItemStack(Items.WOODEN_PICKAXE).isCorrectToolForDrops(state),
                "and a wooden one must not take " + name + ": without the tier tag it would");
        helper.assertTrue(!new ItemStack(Items.DIAMOND_SHOVEL).isCorrectToolForDrops(state),
                "and neither may a diamond of the wrong kind of tool, which is what the mineable tag decides for "
                        + name);
    }
}

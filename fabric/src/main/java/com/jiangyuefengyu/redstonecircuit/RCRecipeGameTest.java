package com.jiangyuefengyu.redstonecircuit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * Tests the mod's recipes by actually running them through the recipe manager.
 *
 * <p>A recipe is JSON, and a file that is wrong in a way the loader tolerates - a pattern that cannot be satisfied, a
 * result count that is off, an ingredient that is not the one intended - is loaded happily and only discovered by a
 * player holding the wrong items. Running the craft here checks the numbers the game will use.
 */
public final class RCRecipeGameTest implements FabricGameTest {

    /** A 3x3 grid from nine stacks. */
    private static CraftingInput grid(ItemStack... stacks) {
        List<ItemStack> items = new ArrayList<>(9);
        for (ItemStack stack : stacks) {
            items.add(stack);
        }
        while (items.size() < 9) {
            items.add(ItemStack.EMPTY);
        }
        return CraftingInput.of(3, 3, items);
    }

    private static CraftingRecipe recipe(GameTestHelper helper, String name) {
        Optional<RecipeHolder<?>> found = helper.getLevel().getRecipeManager()
                .byKey(ResourceLocation.fromNamespaceAndPath(RedstoneCircuit.MODID, name));
        if (found.isEmpty()) {
            helper.fail("no recipe named redstonecircuit:" + name + " was loaded");
            throw new IllegalStateException("unreachable");
        }
        if (!(found.get().value() instanceof CraftingRecipe crafting)) {
            helper.fail("redstonecircuit:" + name + " is not a crafting recipe");
            throw new IllegalStateException("unreachable");
        }
        return crafting;
    }

    private static ItemStack assemble(GameTestHelper helper, CraftingRecipe recipe,
                                      CraftingInput input) {
        return recipe.assemble(input, helper.getLevel().registryAccess());
    }

    /** Eight redstone around a copper ingot, and the design asks for nine dust back. */
    @GameTest(template = "redstonecircuit:empty")
    public void superconductingDustIsNineFromACopperCore(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "superconducting_redstone");

        CraftingInput ring = grid(new ItemStack(Items.REDSTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.REDSTONE));

        helper.assertTrue(recipe.matches(ring, helper.getLevel()),
                "eight redstone around a copper ingot must craft");
        ItemStack result = assemble(helper, recipe, ring);
        helper.assertTrue(result.is(RCRegistry.SUPERCONDUCTING_REDSTONE),
                "and the result is the superconducting dust, got " + result);
        helper.assertTrue(result.getCount() == 9,
                "nine of them, as the design asks, got " + result.getCount());

        // The copper is the point of the recipe: redstone alone must not craft it.
        CraftingInput noCopper = grid(new ItemStack(Items.REDSTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.REDSTONE));
        helper.assertFalse(recipe.matches(noCopper, helper.getLevel()),
                "nine redstone with no copper core is not the recipe");

        // ...and so is the ring: a lone copper ingot is not it either.
        CraftingInput copperOnly = grid(new ItemStack(Items.COPPER_INGOT));
        helper.assertFalse(recipe.matches(copperOnly, helper.getLevel()),
                "nor is a copper ingot on its own");
        helper.succeed();
    }

    /** Glass, redstone, glass in a row - the goggles. */
    @GameTest(template = "redstonecircuit:empty")
    public void gogglesAreGlassRedstoneGlass(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "redstone_goggles");

        CraftingInput row = grid(new ItemStack(Items.GLASS), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.GLASS));
        helper.assertTrue(recipe.matches(row, helper.getLevel()), "glass-redstone-glass must craft");
        helper.assertTrue(assembledIs(helper, recipe, row, RCRegistry.REDSTONE_GOGGLES),
                "into one pair of goggles");

        CraftingInput wrongMiddle = grid(new ItemStack(Items.GLASS), new ItemStack(Items.GLASS),
                new ItemStack(Items.GLASS));
        helper.assertFalse(recipe.matches(wrongMiddle, helper.getLevel()),
                "three glass is not the goggles: the redstone in the middle is");
        helper.succeed();
    }

    /** Three redstone in a column - the wrench. */
    @GameTest(template = "redstonecircuit:empty")
    public void wrenchIsThreeRedstoneInAColumn(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "redstone_wrench");

        CraftingInput column = grid(new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY,
                new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY,
                new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY);
        helper.assertTrue(recipe.matches(column, helper.getLevel()), "three redstone in a column craft");
        helper.assertTrue(assembledIs(helper, recipe, column, RCRegistry.REDSTONE_WRENCH),
                "into one wrench");

        CraftingInput two = grid(new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY,
                new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY);
        helper.assertFalse(recipe.matches(two, helper.getLevel()), "two redstone is not enough");
        helper.succeed();
    }

    /**
     * 逻辑整合器: 铜锭 in the four corners, 青金石 above and below, 下界石英 left and right, 红石粉 in the middle.
     *
     * <p>Each ingredient is checked to be load-bearing one at a time, including the redstone corners the grid must no
     * longer accept: accepting them is exactly what a stale recipe file would produce.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void logicIntegratorIsCopperLapisQuartzAndRedstone(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "logic_integrator");

        CraftingInput given = grid(new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.LAPIS_LAZULI),
                new ItemStack(Items.COPPER_INGOT),
                new ItemStack(Items.QUARTZ), new ItemStack(Items.REDSTONE), new ItemStack(Items.QUARTZ),
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.LAPIS_LAZULI), new ItemStack(Items.COPPER_INGOT));
        helper.assertTrue(recipe.matches(given, helper.getLevel()), "the intended grid must craft");
        helper.assertTrue(assembledIs(helper, recipe, given, RCRegistry.LOGIC_INTEGRATOR_ITEM),
                "into one logic integrator");

        CraftingInput redstoneCorners = grid(new ItemStack(Items.REDSTONE), new ItemStack(Items.LAPIS_LAZULI),
                new ItemStack(Items.REDSTONE),
                new ItemStack(Items.QUARTZ), new ItemStack(Items.REDSTONE), new ItemStack(Items.QUARTZ),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.LAPIS_LAZULI), new ItemStack(Items.REDSTONE));
        helper.assertFalse(recipe.matches(redstoneCorners, helper.getLevel()),
                "红石粉 in the corners must not craft any more");

        CraftingInput ironSides = grid(new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.LAPIS_LAZULI),
                new ItemStack(Items.COPPER_INGOT),
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.REDSTONE), new ItemStack(Items.IRON_INGOT),
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.LAPIS_LAZULI), new ItemStack(Items.COPPER_INGOT));
        helper.assertFalse(recipe.matches(ironSides, helper.getLevel()),
                "and neither may 铁锭 left and right: the two sides are 下界石英");

        CraftingInput copperSides = grid(new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.LAPIS_LAZULI),
                new ItemStack(Items.COPPER_INGOT),
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.REDSTONE), new ItemStack(Items.COPPER_INGOT),
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.LAPIS_LAZULI), new ItemStack(Items.COPPER_INGOT));
        helper.assertFalse(recipe.matches(copperSides, helper.getLevel()),
                "nor 铜锭 in the middle: the middle is still 红石粉");

        CraftingInput redstoneLapis = grid(new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.COPPER_INGOT),
                new ItemStack(Items.QUARTZ), new ItemStack(Items.REDSTONE), new ItemStack(Items.QUARTZ),
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.REDSTONE), new ItemStack(Items.COPPER_INGOT));
        helper.assertFalse(recipe.matches(redstoneLapis, helper.getLevel()),
                "nor 红石粉 above and below, which is where the 青金石 goes");
        helper.succeed();
    }

    private static boolean assembledIs(GameTestHelper helper, CraftingRecipe recipe,
                                       CraftingInput input, Item expected) {
        ItemStack result = assemble(helper, recipe, input);
        return result.is(expected) && result.getCount() == 1;
    }

    /** Guards against the enum and the item registry drifting apart: they are two halves of one idea. */
    @GameTest(template = "redstonecircuit:empty")
    public void theSuperconductingItemIsTheSuperconductingComponent(GameTestHelper helper) {
        helper.assertTrue(HostRules.componentFor(new ItemStack(RCRegistry.SUPERCONDUCTING_REDSTONE))
                        == ComponentType.SUPERCONDUCTOR,
                "placing the item must install the component it is named after");
        helper.assertTrue(HostRules.itemFor(ComponentType.SUPERCONDUCTOR)
                        .is(RCRegistry.SUPERCONDUCTING_REDSTONE),
                "and taking it back out must return the same item");
        helper.assertTrue(ComponentType.SUPERCONDUCTOR.isWire(),
                "which is a wire: it reads and emits on every side");
        helper.assertTrue(ComponentType.SUPERCONDUCTOR.hopCost() == 0,
                "and it is the one wire whose hops cost nothing");
        helper.assertTrue(ComponentType.DUST.hopCost() == 1,
                "while ordinary dust keeps vanilla's cost");
        helper.succeed();
    }

    /**
     * Redstone / cobblestone / nether star: the corners are dust, the four edges cobblestone, the middle a nether star.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theStructureBlockRecipeIsARedstoneRingAroundCobblestoneAndANetherStar(
            GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "redstone_structure_block");

        CraftingInput correct = grid(
                new ItemStack(Items.REDSTONE), new ItemStack(Items.COBBLESTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.NETHER_STAR), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.COBBLESTONE), new ItemStack(Items.REDSTONE));
        helper.assertTrue(recipe.matches(correct, helper.getLevel()),
                "redstone corners, cobblestone edges and a nether star in the middle must craft");
        helper.assertTrue(assembledIs(helper, recipe, correct,
                        RCRegistry.REDSTONE_STRUCTURE_BLOCK_ITEM),
                "into exactly one controller");

        // The nether star is the gate on this feature (it takes a wither), so the recipe must not accept
        // anything else there: cobblestone in the middle is not the recipe.
        CraftingInput noNetherStar = grid(
                new ItemStack(Items.REDSTONE), new ItemStack(Items.COBBLESTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.COBBLESTONE), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.COBBLESTONE), new ItemStack(Items.REDSTONE));
        helper.assertFalse(recipe.matches(noNetherStar, helper.getLevel()),
                "all cobblestone with a nether star missing is not the recipe");

        // ...and the corners really are redstone: cobblestone there is a different, cheaper block of the mod.
        CraftingInput cobblestoneCorners = grid(
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.COBBLESTONE), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.NETHER_STAR), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.COBBLESTONE), new ItemStack(Items.COBBLESTONE));
        helper.assertFalse(recipe.matches(cobblestoneCorners, helper.getLevel()),
                "the four corners have to be redstone dust");
        helper.succeed();
    }

    /** The product has no recipe of its own: it comes out of a capture, as the design decides. */
    @GameTest(template = "redstonecircuit:empty")
    public void theStructureItselfHasNoRecipe(GameTestHelper helper) {
        helper.assertTrue(helper.getLevel().getRecipeManager()
                        .byKey(ResourceLocation.fromNamespaceAndPath(RedstoneCircuit.MODID, "structure"))
                        .isEmpty(),
                "redstonecircuit:structure must not be craftable - a structure can only be captured");
        helper.succeed();
    }

    /**
     * 方块放置器: 中间一个铁锭，正上下左右为任意木板，四个角为圆石.
     *
     * <p>"任意木板" is the half that has to be exercised twice - once with oak and once with birch - because a recipe
     * written against one wood type would look correct in the file and refuse three quarters of the planks a player
     * owns. The negative controls are the three materials each taking a cell that belongs to another.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void blockPlacerIsCobblePlanksAndIron(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "block_placer");

        CraftingInput given = grid(
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.COBBLESTONE));
        helper.assertTrue(recipe.matches(given, helper.getLevel()), "the intended grid must craft");
        helper.assertTrue(assemble(helper, recipe, given).is(RCRegistry.BLOCK_PLACER_ITEM),
                "and the result is the block placer");

        CraftingInput birch = grid(
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.BIRCH_PLANKS), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.BIRCH_PLANKS), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.BIRCH_PLANKS),
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.BIRCH_PLANKS), new ItemStack(Items.COBBLESTONE));
        helper.assertTrue(recipe.matches(birch, helper.getLevel()),
                "任意木板 is a tag and not one wood type: birch must craft it as readily as oak");

        CraftingInput planksMiddle = grid(
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.COBBLESTONE));
        helper.assertFalse(recipe.matches(planksMiddle, helper.getLevel()),
                "the middle is an iron ingot, not another plank");

        CraftingInput ironCorners = grid(
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.IRON_INGOT),
                new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.OAK_PLANKS),
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.OAK_PLANKS), new ItemStack(Items.IRON_INGOT));
        helper.assertFalse(recipe.matches(ironCorners, helper.getLevel()), "the four corners are cobblestone");

        CraftingInput redstoneSides = grid(
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.REDSTONE), new ItemStack(Items.COBBLESTONE),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.IRON_INGOT), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.COBBLESTONE), new ItemStack(Items.REDSTONE), new ItemStack(Items.COBBLESTONE));
        helper.assertFalse(recipe.matches(redstoneSides, helper.getLevel()),
                "redstone in the four side cells must not craft it either");
        helper.succeed();
    }

    /**
     * 红石变压器: 中间横着三个红石，四个角下界石英，正上下铜锭.
     *
     * <p>The negative control is the middle row: copper there would be a plausible-looking file that crafts nothing of
     * the sort, and the row of dust is what the device is made of.
     *
     * <p>Nether quartz is {@code minecraft:quartz}, not {@code minecraft:nether_quartz}: the wrong id makes the data
     * pack refuse the whole file at server start while a test that only reads the recipe's <em>text</em> still passes,
     * so this test loads the recipe and crafts it.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theTransformerRecipeIsQuartzCornersCopperEdgesAndARedstoneRow(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "transformer");

        CraftingInput given = grid(
                new ItemStack(Items.QUARTZ), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.QUARTZ),
                new ItemStack(Items.REDSTONE), new ItemStack(Items.REDSTONE), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.QUARTZ), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.QUARTZ));
        helper.assertTrue(recipe.matches(given, helper.getLevel()), "the intended grid must craft");
        helper.assertTrue(assemble(helper, recipe, given).is(RCRegistry.TRANSFORMER_ITEM),
                "and the result is 红石变压器");

        CraftingInput copperRow = grid(
                new ItemStack(Items.QUARTZ), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.QUARTZ),
                new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.COPPER_INGOT),
                new ItemStack(Items.QUARTZ), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.QUARTZ));
        helper.assertFalse(recipe.matches(copperRow, helper.getLevel()),
                "中间那一横排必须是三个红石，铜锭在那里就不是这个装置了");

        CraftingInput quartzRow = grid(
                new ItemStack(Items.QUARTZ), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.QUARTZ),
                new ItemStack(Items.QUARTZ), new ItemStack(Items.QUARTZ), new ItemStack(Items.QUARTZ),
                new ItemStack(Items.QUARTZ), new ItemStack(Items.COPPER_INGOT), new ItemStack(Items.QUARTZ));
        helper.assertFalse(recipe.matches(quartzRow, helper.getLevel()),
                "and neither is a row of quartz - the two halves are not interchangeable");
        helper.succeed();
    }

    /**
     * 时钟源: 中间一个钟（时钟那个钟），左右金锭，上下下界石英 - a clock with gold beside it and quartz above and below.
     *
     * <p>The negative control is the gold moved up into the quartz cell: a plausible-looking grid that must craft
     * nothing, because the two materials are what say which device this is.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theClockRecipeIsAClockBetweenGoldAndQuartz(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "clock");

        CraftingInput given = grid(
                ItemStack.EMPTY, new ItemStack(Items.QUARTZ), ItemStack.EMPTY,
                new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.CLOCK), new ItemStack(Items.GOLD_INGOT),
                ItemStack.EMPTY, new ItemStack(Items.QUARTZ), ItemStack.EMPTY);
        helper.assertTrue(recipe.matches(given, helper.getLevel()), "the intended grid must craft");
        helper.assertTrue(assemble(helper, recipe, given).is(RCRegistry.CLOCK_ITEM),
                "and the result is 时钟源");

        CraftingInput goldAbove = grid(
                ItemStack.EMPTY, new ItemStack(Items.GOLD_INGOT), ItemStack.EMPTY,
                new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.CLOCK), new ItemStack(Items.GOLD_INGOT),
                ItemStack.EMPTY, new ItemStack(Items.QUARTZ), ItemStack.EMPTY);
        helper.assertFalse(recipe.matches(goldAbove, helper.getLevel()),
                "上下必须是下界石英：金锭在上面就不是这个装置了");
        helper.succeed();
    }

    /**
     * 信号延长器: 中间红石，两侧青金石，上下金锭，四角铁锭 - redstone in the middle, lapis beside it, gold above and below,
     * iron in the four corners.
     *
     * <p>The negative control swaps the lapis and the iron: a plausible-looking grid that must craft nothing, because
     * the two materials are what say which device this is.
     */
    @GameTest(template = "redstonecircuit:empty")
    public void theExtenderRecipeIsIronGoldLapisAndRedstone(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "extender");

        CraftingInput given = grid(
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.IRON_INGOT),
                new ItemStack(Items.LAPIS_LAZULI), new ItemStack(Items.REDSTONE), new ItemStack(Items.LAPIS_LAZULI),
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.IRON_INGOT));
        helper.assertTrue(recipe.matches(given, helper.getLevel()), "the intended grid must craft");
        helper.assertTrue(assemble(helper, recipe, given).is(RCRegistry.EXTENDER_ITEM),
                "and the result is 信号延长器");

        CraftingInput swapped = grid(
                new ItemStack(Items.LAPIS_LAZULI), new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.LAPIS_LAZULI),
                new ItemStack(Items.IRON_INGOT), new ItemStack(Items.REDSTONE), new ItemStack(Items.IRON_INGOT),
                new ItemStack(Items.LAPIS_LAZULI), new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.LAPIS_LAZULI));
        helper.assertFalse(recipe.matches(swapped, helper.getLevel()),
                "两侧青金石、四角铁锭，换了就不是这个装置了");
        helper.succeed();
    }
}

package com.jiangyuefengyu.redstonecircuit;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Tests the mod's recipes by actually running them through the recipe manager.
 *
 * <p>A recipe is JSON, and a JSON file that is wrong in a way the loader tolerates - a pattern that
 * cannot be satisfied, a result count that is off, an ingredient that is not the one the design asks
 * for - is loaded happily and only discovered by a player holding the wrong items. Running the craft
 * here means the numbers in the design document are checked against the numbers the game will use.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class RCRecipeGameTest {

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
    @GameTest(template = "empty")
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
        helper.assertTrue(result.is(RCRegistry.SUPERCONDUCTING_REDSTONE.get()),
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
    @GameTest(template = "empty")
    public void gogglesAreGlassRedstoneGlass(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "redstone_goggles");

        CraftingInput row = grid(new ItemStack(Items.GLASS), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.GLASS));
        helper.assertTrue(recipe.matches(row, helper.getLevel()), "glass-redstone-glass must craft");
        helper.assertTrue(assembledIs(helper, recipe, row, RCRegistry.REDSTONE_GOGGLES.get()),
                "into one pair of goggles");

        CraftingInput wrongMiddle = grid(new ItemStack(Items.GLASS), new ItemStack(Items.GLASS),
                new ItemStack(Items.GLASS));
        helper.assertFalse(recipe.matches(wrongMiddle, helper.getLevel()),
                "three glass is not the goggles: the redstone in the middle is");
        helper.succeed();
    }

    /** Three redstone in a column - the wrench. */
    @GameTest(template = "empty")
    public void wrenchIsThreeRedstoneInAColumn(GameTestHelper helper) {
        CraftingRecipe recipe = recipe(helper, "redstone_wrench");

        CraftingInput column = grid(new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY,
                new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY,
                new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY);
        helper.assertTrue(recipe.matches(column, helper.getLevel()), "three redstone in a column craft");
        helper.assertTrue(assembledIs(helper, recipe, column, RCRegistry.REDSTONE_WRENCH.get()),
                "into one wrench");

        CraftingInput two = grid(new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY,
                new ItemStack(Items.REDSTONE), ItemStack.EMPTY, ItemStack.EMPTY);
        helper.assertFalse(recipe.matches(two, helper.getLevel()), "two redstone is not enough");
        helper.succeed();
    }

    private static boolean assembledIs(GameTestHelper helper, CraftingRecipe recipe,
                                       CraftingInput input, Item expected) {
        ItemStack result = assemble(helper, recipe, input);
        return result.is(expected) && result.getCount() == 1;
    }

    /** Guards against the enum and the item registry drifting apart: they are two halves of one idea. */
    @GameTest(template = "empty")
    public void theSuperconductingItemIsTheSuperconductingComponent(GameTestHelper helper) {
        helper.assertTrue(HostRules.componentFor(new ItemStack(RCRegistry.SUPERCONDUCTING_REDSTONE.get()))
                        == ComponentType.SUPERCONDUCTOR,
                "placing the item must install the component it is named after");
        helper.assertTrue(HostRules.itemFor(ComponentType.SUPERCONDUCTOR)
                        .is(RCRegistry.SUPERCONDUCTING_REDSTONE.get()),
                "and taking it back out must return the same item");
        helper.assertTrue(ComponentType.SUPERCONDUCTOR.isWire(),
                "which is a wire: it reads and emits on every side");
        helper.assertTrue(ComponentType.SUPERCONDUCTOR.hopCost() == 0,
                "and it is the one wire whose hops cost nothing");
        helper.assertTrue(ComponentType.DUST.hopCost() == 1,
                "while ordinary dust keeps vanilla's cost");
        helper.succeed();
    }
}

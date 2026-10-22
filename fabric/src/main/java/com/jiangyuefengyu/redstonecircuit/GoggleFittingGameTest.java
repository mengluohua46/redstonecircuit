package com.jiangyuefengyu.redstonecircuit;

import java.util.List;
import java.util.Optional;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * <b>红石眼镜 fit into any helmet, in the inventory's own 2×2 grid, and only once.</b>
 *
 * <p>What is asked here is the whole point of the feature: the <em>same</em> helmet comes back (its item, its damage,
 * its own components), it now answers {@code isGoggles} - the one question the renderer, the Jade overlay and every
 * bare-hand rule read - and a helmet that already carries the goggles does not match the recipe at all, so a second
 * fitting is not refused later but simply does not exist.
 *
 * <p>Vanilla helmets are the stand-in for "any helmet": the recipe asks {@code ArmorItem && helmet slot}, so a modded
 * helmet is the same question with a different item, and no enum of helmets exists here to go stale.
 */
public final class GoggleFittingGameTest implements FabricGameTest {

    @GameTest(template = "redstonecircuit:empty")
    public void anyHelmetTakesTheGogglesInTheSmallestGrid(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HolderLookup.Provider registries = level.registryAccess();
        ItemStack goggles = new ItemStack(RCRegistry.REDSTONE_GOGGLES);

        for (Item helmet : List.of(Items.IRON_HELMET, Items.DIAMOND_HELMET, Items.CHAINMAIL_HELMET,
                Items.GOLDEN_HELMET, Items.NETHERITE_HELMET)) {
            ItemStack worn = new ItemStack(helmet);
            worn.setDamageValue(7);
            CraftingInput grid = grid(worn, goggles);
            Optional<RecipeHolder<CraftingRecipe>> found = level.getRecipeManager()
                    .getRecipeFor(RecipeType.CRAFTING, grid, level);
            helper.assertTrue(found.isPresent(), "the inventory's 2x2 grid has to fit goggles into " + helmet);

            ItemStack result = found.get().value().assemble(grid, registries);
            helper.assertTrue(result.is(helmet), "the helmet itself comes back, got " + result);
            helper.assertTrue(result.getCount() == 1, "one helmet at a time, got " + result.getCount());
            helper.assertTrue(result.getDamageValue() == 7,
                    "its damage is the player's and has to survive the fitting, got " + result.getDamageValue());
            helper.assertTrue(result.has(RCRegistry.GOGGLES), "with the goggles inside it");
            helper.assertTrue(RCRegistry.isGoggles(result),
                    "and that one answer is what makes every ability gate see them");
        }

        // Fitted once: the second one has no recipe to be refused by.
        ItemStack fitted = new ItemStack(Items.IRON_HELMET);
        fitted.set(RCRegistry.GOGGLES, Unit.INSTANCE);
        helper.assertTrue(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
                        grid(fitted, goggles), level).isEmpty(),
                "a helmet that already carries the goggles must not match anything");

        helper.assertTrue(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
                        grid(new ItemStack(Items.IRON_INGOT), goggles), level).isEmpty(),
                "only headwear takes them");
        helper.assertTrue(level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING,
                        grid(new ItemStack(Items.IRON_HELMET), new ItemStack(Items.DIAMOND_HELMET)), level).isEmpty(),
                "two helmets and no goggles is not this recipe");
        helper.succeed();
    }

    /** The inventory's own 2×2 grid: four slots, two of them filled - which is how a player's grid arrives. */
    private static CraftingInput grid(ItemStack first, ItemStack second) {
        return CraftingInput.of(2, 2, List.of(first, second, ItemStack.EMPTY, ItemStack.EMPTY));
    }
}

package com.jiangyuefengyu.redstonecircuit.recipe;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.HolderLookup;
import net.minecraft.util.Unit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;

/**
 * <b>红石眼镜 into somebody else's helmet.</b> One bare helmet plus one pair of goggles in, the same helmet back with
 * the goggles inside it - the same item, its own name, enchantments and damage intact.
 *
 * <p>A recipe of this mod's own rather than one data file per helmet: the point is that <b>any</b> helmet works,
 * including helmets this mod has never heard of, and a recipe list cannot enumerate what a mod it does not depend on
 * registers. {@link RCRegistry#isBareHelmet} is the question, so a helmet that already carries the goggles does not
 * match - a second fitting has no recipe at all, which is a cleaner answer than a rule refusing it later.
 *
 * <p>Two ingredients, so it fits the inventory's own 2×2 grid as well as a crafting table: both menus ask the same
 * {@code RecipeType.CRAFTING} question, and only the grid's size decides which recipes can match
 * ({@link #canCraftInDimensions}).
 */
public class GoggleFittingRecipe extends CustomRecipe {

    public GoggleFittingRecipe(CraftingBookCategory category) {
        super(category);
    }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return bareHelmetOf(input) != null;
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack helmet = bareHelmetOf(input);
        if (helmet == null) {
            return ItemStack.EMPTY;
        }
        // A copy, not a new stack: the helmet's own components (name, enchantments, damage, another mod's data) are
        // the player's, and fitting the goggles may not quietly drop any of them.
        ItemStack fitted = helmet.copyWithCount(1);
        fitted.set(RCRegistry.GOGGLES, Unit.INSTANCE);
        return fitted;
    }

    /** The inventory's 2×2 and a crafting table's 3×3 alike: two ingredients need two slots somewhere. */
    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return width * height >= 2;
    }

    /** The result is the item that was put in, so there is no one stack to show in a recipe book. */
    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return RCRegistry.GOGGLE_FITTING_SERIALIZER;
    }

    /**
     * The helmet this grid would fit goggles into, or {@code null} when the grid is not exactly one bare helmet and one
     * pair of goggles.
     *
     * <p>Exactly: a grid holding two helmets, two pairs, a helmet and an unrelated item, or three of anything is not
     * this recipe. Being strict here is what keeps the answer honest - "the goggles went into the wrong thing" is not
     * a failure a player can undo.
     */
    private static ItemStack bareHelmetOf(CraftingInput input) {
        ItemStack helmet = ItemStack.EMPTY;
        boolean pair = false;
        int filled = 0;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            if (++filled > 2) {
                return null;
            }
            if (stack.is(RCRegistry.REDSTONE_GOGGLES)) {
                if (pair) {
                    return null;
                }
                pair = true;
            } else if (RCRegistry.isBareHelmet(stack)) {
                if (!helmet.isEmpty()) {
                    return null;
                }
                helmet = stack;
            } else {
                return null;
            }
        }
        return pair && !helmet.isEmpty() ? helmet : null;
    }
}

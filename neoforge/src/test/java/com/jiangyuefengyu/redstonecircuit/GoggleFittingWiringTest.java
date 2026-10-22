package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The fitting recipe's three load-bearing decisions, each one line that is invisible in game until it is wrong.
 *
 * <h2>Which three</h2>
 * <ul>
 *   <li><b>One funnel for "are the goggles on":</b> {@code RCRegistry.isGoggles} has to answer for a merged helmet as
 *       well, or the ability arrives in whichever places happened to be edited and nowhere else.</li>
 *   <li><b>Exactly one helmet and one pair:</b> a recipe that matched loosely would fit goggles into a chestplate or
 *       swallow a second helmet, and neither is something a player can undo.</li>
 *   <li><b>Two ingredients and no recipe for a second fitting:</b> two, so the inventory's 2×2 grid can hold it;
 *       "already fitted does not match", so the refusal is the absence of a recipe rather than a message later.</li>
 * </ul>
 */
class GoggleFittingWiringTest {

    @Test
    @DisplayName("能力只有一个入口：戴没戴眼镜，合成过的头盔也算戴了")
    void oneFunnelAnswersForAFittedHelmet() throws IOException {
        String registry = read("src/main/java/com/jiangyuefengyu/redstonecircuit/RCRegistry.java");
        assertTrue(registry.contains("stack.is(REDSTONE_GOGGLES.get()) || stack.has(GOGGLES.get())"),
                "the item and the marker are the same answer, in the one method every gate calls");
        assertTrue(registry.contains("public static boolean isBareHelmet(ItemStack stack)"),
                "and the recipe's question is next to it, so 'a helmet' means one thing in both places");
        assertTrue(registry.contains("if (stack.isEmpty() || isGoggles(stack)) {"),
                "a stack that already has them is not a target: that is where 'no second fitting' lives");
    }

    @Test
    @DisplayName("配方只吃两件：一件裸头盔 + 一副眼镜，物品栏 2×2 放得下")
    void theBasketIsExactlyTwoAndFitsTheSmallGrid() throws IOException {
        String recipe = read("src/main/java/com/jiangyuefengyu/redstonecircuit/recipe/GoggleFittingRecipe.java");
        assertTrue(recipe.contains("extends CustomRecipe"),
                "a CRAFTING recipe of our own, or the 2x2 grid would need a data file per helmet");
        assertTrue(recipe.contains("return width * height >= 2;"),
                "two ingredients: the inventory grid is 2x2, and this is the line that lets it in");
        assertTrue(recipe.contains("if (++filled > 2)") && recipe.contains("if (pair) {")
                        && recipe.contains("if (!helmet.isEmpty()) {"),
                "more than one helmet, more than one pair, or a third item, is not this recipe");
        assertTrue(recipe.contains("helmet.copyWithCount(1)"),
                "the player's helmet comes back as a copy of itself, which is what keeps its own components");
        assertTrue(recipe.contains("public boolean isSpecial() {") && recipe.contains("return true;"),
                "special, because the result is the input: a recipe book entry would have nothing to show");
    }

    @Test
    @DisplayName("合成过的头盔自己会说一句，两份语言都有")
    void aFittedHelmetSaysSoInBothLanguages() throws IOException {
        String events = read("src/main/java/com/jiangyuefengyu/redstonecircuit/client/GoggleTooltipEvents.java");
        assertTrue(events.contains("event.getItemStack().has(RCRegistry.GOGGLES.get())"),
                "somebody else's item cannot be told to draw a line, so the line is added where tooltips are drawn");
        assertTrue(events.contains("ItemTooltipEvent"),
                "one event for every tooltip, instead of a mixin into a class we do not own");

        for (String lang : List.of("zh_cn.json", "en_us.json")) {
            String text = read("src/main/resources/assets/redstonecircuit/lang/" + lang);
            assertTrue(text.contains("\"item.redstonecircuit.redstone_goggles.fitted\""),
                    lang + " is missing the line a fitted helmet shows, which would show a raw key instead");
            assertTrue(text.contains("\"item.redstonecircuit.redstone_goggles.tip.fitted\""),
                    lang + " is missing how to fit them, and no player would guess the recipe");
        }
    }

    @Test
    @DisplayName("判据是 Equipable（1.21 的头戴物），机械动力的护目镜也在标签里登记了")
    void theRuleIsEquipableAndCreatesGogglesAreNamed() throws IOException {
        String registry = read("src/main/java/com/jiangyuefengyu/redstonecircuit/RCRegistry.java");
        assertTrue(registry.contains("stack.getItem() instanceof Equipable wearable"),
                "ArmorItem is only armour: Create's engineer's goggles are a plain Item implements Equipable, and so "
                        + "is every other wearable a mod builds the 1.21 way");
        assertTrue(registry.contains("wearable.getEquipmentSlot() == EquipmentSlot.HEAD"),
                "and the slot is what makes it headwear, not the class it happens to extend");

        String tag = read("src/main/resources/data/redstonecircuit/tags/item/goggle_fit_targets.json");
        assertTrue(tag.contains("\"create:goggles\""),
                "the one mod we know by name is registered explicitly, so the promise survives the rule changing");
        assertTrue(tag.contains("\"required\": false"),
                "and it is optional: a pack without Create has to load this tag, not choke on a missing item");
    }

    private static String read(String relative) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(relative);
        Path direct = Path.of(relative);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath());
    }
}

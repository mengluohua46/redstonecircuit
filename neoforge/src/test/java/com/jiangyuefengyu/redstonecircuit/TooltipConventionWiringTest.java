package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tooltip convention, and one item that may not be handed out.
 *
 * <p>Both are conventions rather than features, and a convention is exactly what a source guard is for: nine tooltips
 * written by hand are nine chances to drift back into a manual, and a summary key that is missing from one of the two
 * language files shows a player the raw key instead of a sentence.
 */
class TooltipConventionWiringTest {

    private static final List<String> TOOLTIP_SOURCES = List.of(
            "src/main/java/com/jiangyuefengyu/redstonecircuit/block/RedstoneStructureBlock.java",
            "src/main/java/com/jiangyuefengyu/redstonecircuit/block/LogicIntegratorBlock.java",
            "src/main/java/com/jiangyuefengyu/redstonecircuit/block/BlockPlacerBlock.java",
            "src/main/java/com/jiangyuefengyu/redstonecircuit/block/TransmissionGateBlock.java",
            "src/main/java/com/jiangyuefengyu/redstonecircuit/block/ControllerBlock.java",
            "src/main/java/com/jiangyuefengyu/redstonecircuit/block/StructureBlock.java",
            "src/main/java/com/jiangyuefengyu/redstonecircuit/item/RedstoneWrenchItem.java",
            "src/main/java/com/jiangyuefengyu/redstonecircuit/item/RedstoneGogglesItem.java",
            "src/main/java/com/jiangyuefengyu/redstonecircuit/item/SuperconductingRedstoneItem.java",
            "src/main/java/com/jiangyuefengyu/redstonecircuit/item/RotationRodItem.java");

    @Test
    @DisplayName("扳手式 tooltip：默认只有一行提示，按住 [Shift] 才显示概要（靠 TooltipFlag，不是 F3+H）")
    void onlyTheHintByDefaultAndTheSummaryBehindShift() throws IOException {
        String helper = read("src/main/java/com/jiangyuefengyu/redstonecircuit/RcTooltips.java");

        assertTrue(helper.contains("public static void append(List<Component> tooltip, TooltipFlag flag, String summaryKey, Component... details)"),
                "one entry point, so the nine call sites cannot each invent their own layout");
        assertTrue(helper.contains("if (!flag.hasShiftDown()) {")
                        && helper.contains("tooltip.add(Component.translatable(HOLD_SHIFT).withStyle(ChatFormatting.DARK_GRAY));")
                        && helper.contains("return;"),
                "with nothing held the tooltip is one line, and it is the hint: showing the summary and "
                        + "hiding only the details would be a different reading of the same rule");
        assertTrue(helper.indexOf("if (!flag.hasShiftDown()) {") < helper.indexOf("tooltip.add(summary(summaryKey));"),
                "and the summary comes AFTER that gate, i.e. only while [Shift] is held");
        assertTrue(helper.contains("HOLD_SHIFT = \"tooltip.redstonecircuit.hold_shift\";"),
                "with the hint line named by a constant, so the two language files and the code cannot disagree");
        assertTrue(helper.contains("for (Component detail : details) {"),
                "and every detail follows the summary once [Shift] is held: nothing is dropped, only "
                        + "moved behind the key");
    }

    @Test
    @DisplayName("九个 tooltip 都走同一个入口，而且它们的概要键在两份语言文件里都存在")
    void everyTooltipUsesTheHelperAndEverySummaryHasBothLanguages() throws IOException {
        List<String> summaries = new ArrayList<>();
        Pattern summaryKey = Pattern.compile("RcTooltips\\.append\\(tooltip, flag,\\s*\"([^\"]+)\"");

        for (String source : TOOLTIP_SOURCES) {
            String text = read(source);
            assertTrue(text.contains("RcTooltips.append("),
                    source + " must build its tooltip through RcTooltips, or it is the tenth hand-written one");
            assertFalse(text.contains("tooltip.add(Component.translatable("),
                    source + " still adds a line by hand: that is the shape the convention replaced");
            Matcher matcher = summaryKey.matcher(text.replace("\n", " ").replaceAll("\\s+", " "));
            assertTrue(matcher.find(), source + ": the summary key could not be read out of the call");
            summaries.add(matcher.group(1));
        }
        assertFalse(summaries.isEmpty(), "no tooltips were checked at all");

        for (String lang : List.of("zh_cn.json", "en_us.json")) {
            String text = read("src/main/resources/assets/redstonecircuit/lang/" + lang);
            assertTrue(text.contains("\"tooltip.redstonecircuit.hold_shift\""), lang + ": the [Shift] hint");
            for (String key : summaries) {
                assertTrue(text.contains("\"" + key + "\""),
                        lang + " is missing the summary " + key + ", which would show a player the raw key in game");
            }
        }
    }

    @Test
    @DisplayName("「结构」不在创造栏里，但它仍然注册着（捕获路径要把它递回来）")
    void theStructureBlockIsNotOnOffer() throws IOException {
        String registry = read("src/main/java/com/jiangyuefengyu/redstonecircuit/RCRegistry.java");

        assertFalse(registry.contains("output.accept(STRUCTURE_ITEM.get())"),
                "it is the block a capture is carried in, i.e. an implementation"
                        + " detail of 红石结构方块, and the machine is what a player is meant to hold");
        assertTrue(registry.contains("public static final DeferredHolder<Item, StructureBlockItem> STRUCTURE_ITEM ="),
                "while the item itself stays registered: a release hands it back, and two game tests use it");
    }

    private static String read(String relative) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(relative);
        Path direct = Path.of(relative);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath()
                + "; this guard reads the file on purpose");
    }
}

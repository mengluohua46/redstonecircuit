package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two repeater families and the weather detector wear the same [Shift] tooltip as everything else.
 *
 * <h2>Why this is its own guard</h2>
 * The three repeaters are <b>one class registered three times</b> ({@code VariableRepeaterBlock}, told apart by its
 * delay multiplier and whether it keeps the strength), so its tooltip is three sentences chosen inside one method - and
 * the convention guard can only read the first key out of such a call. What can be checked is what a player sees: every
 * one of the three ids has a summary and a detail in <b>both</b> languages, and the shared class really names all three,
 * so a registration cannot quietly fall through onto another repeater's sentence.
 */
class RepeaterWeatherTooltipTest {

    /** The three ids the shared repeater class serves, and the one the detector serves. */
    private static final List<String> REPEATERS =
            List.of("retaining_repeater", "double_repeater", "double_retaining_repeater");

    @Test
    @DisplayName("四个方块各有一行提示和一行详情，两份语言文件都不缺")
    void everyOneHasBothLinesInBothLanguages() throws IOException {
        List<String> ids = List.of("retaining_repeater", "double_repeater", "double_retaining_repeater",
                "weather_detector");
        for (String lang : List.of("zh_cn.json", "en_us.json")) {
            String text = read("src/main/resources/assets/redstonecircuit/lang/" + lang);
            for (String id : ids) {
                String summary = "block.redstonecircuit." + id + ".tip";
                assertTrue(text.contains("\"" + summary + "\""),
                        lang + " is missing " + summary + ": the item would show the raw key instead of a sentence");
                assertTrue(text.contains("\"" + summary + ".detail\""),
                        lang + " is missing " + summary + ".detail: holding [Shift] would show the summary and stop");
            }
        }
    }

    @Test
    @DisplayName("三个中继器各说各的话，没有一个落到别人的句子上去")
    void theThreeRepeatersNameTheirOwnKeys() throws IOException {
        String repeaters = read("src/main/java/com/jiangyuefengyu/redstonecircuit/block/VariableRepeaterBlock.java");

        for (String id : REPEATERS) {
            assertTrue(repeaters.contains("\"block.redstonecircuit." + id + ".tip\""),
                    "a registration showing another repeater's sentence is a bug you only see in hand: " + id);
        }
        Matcher calls = Pattern.compile("RcTooltips\\.append\\(").matcher(repeaters);
        int handovers = 0;
        while (calls.find()) {
            handovers++;
        }
        assertEquals(REPEATERS.size(), handovers,
                "one call per shape: a single call with a computed key would pass the language check above while "
                        + "showing the same sentence for all three");

        assertTrue(repeaters.contains("delayMultiplier == 1") && repeaters.contains("preservesStrength"),
                "and which sentence it wears is read off the same two flags that decide what it does, so a fourth "
                        + "registration cannot slip in without being answered for here");

        String weather = read("src/main/java/com/jiangyuefengyu/redstonecircuit/block/WeatherDetectorBlock.java");
        assertTrue(weather.contains("\"block.redstonecircuit.weather_detector.tip\""),
                "the detector has one setting the player cycles, and one sentence about it");
    }

    /** One of the mod's own files, addressed from this project's directory. */
    private static String read(String relative) throws IOException {
        Path direct = Path.of(relative);
        if (Files.isRegularFile(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath()
                + "; this guard reads the file on purpose");
    }
}

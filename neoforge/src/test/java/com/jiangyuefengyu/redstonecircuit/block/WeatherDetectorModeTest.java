package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock.Mode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The weather detector's three settings: which sky each one answers, and that nothing else forgot them.
 *
 * <p>The rule itself is one line of arithmetic and is tested here as a pure function. The rest of this class is about
 * the ways a three-valued setting can be silently half-applied - a blockstate that lists only two-value combinations, a
 * language file that never learned the word "thunder" - because those failures do not throw: the block simply never
 * renders, or the message shows a raw key.
 */
class WeatherDetectorModeTest {

    @Test
    @DisplayName("the three settings are mutually exclusive, and thunder is asked first")
    void theSkyMapsToOneSetting() {
        assertEquals(Mode.CLEAR, WeatherDetectorBlock.weatherOf(false, false),
                "no rain and no thunder is clear weather");
        assertEquals(Mode.RAIN, WeatherDetectorBlock.weatherOf(true, false),
                "rain without thunder is the rain setting, not the thunder one");
        assertEquals(Mode.THUNDER, WeatherDetectorBlock.weatherOf(true, true),
                "a storm is its own setting even though it is raining");
        assertEquals(Mode.THUNDER, WeatherDetectorBlock.weatherOf(false, true),
                "and thunder wins on its own: the settings have to stay mutually exclusive");
    }

    @Test
    @DisplayName("a right-click walks 晴 -> 雨 -> 雷 -> 晴, which is the property's declaration order")
    void theClickCycleIsTheDeclarationOrder() {
        assertEquals(List.of(Mode.CLEAR, Mode.RAIN, Mode.THUNDER),
                List.copyOf(WeatherDetectorBlock.WEATHER.getPossibleValues()),
                "BlockState#cycle follows this list, so it is the cycle the player sees");

        assertEquals("weather", WeatherDetectorBlock.WEATHER.getName(),
                "the property is named for the setting it holds, not for a two-valued flag");
        assertEquals("active", WeatherDetectorBlock.ACTIVE.getName(),
                "and the answer keeps its own property, which is what makes 'did it change?' one comparison");
    }

    @Test
    @DisplayName("each setting serialises to the word the language files and Jade use")
    void theNamesAreTheKeys() {
        for (Mode mode : Mode.values()) {
            assertEquals(mode.name().toLowerCase(java.util.Locale.ROOT), mode.getSerializedName());
            assertEquals(mode.getSerializedName(), mode.key(),
                    "the chat message and the Jade line are built from this one name");
        }
    }

    @Test
    @DisplayName("the blockstate answers every weather and every answer, or the block would not draw")
    void theBlockstateCoversAllSixStates() throws IOException {
        String json = readResource("blockstates", "weather_detector.json");
        for (Mode mode : Mode.values()) {
            for (String active : List.of("true", "false")) {
                String variant = "\"weather=" + mode.key() + ",active=" + active + "\"";
                assertTrue(json.contains(variant),
                        "the blockstate has no variant for " + variant + ", which leaves the block with "
                                + "no model in that state");
            }
        }
        assertFalse(json.contains("rainy="),
                "the block has no two-valued 'rainy' variant, so a blockstate still listing it "
                        + "would never match anything");
    }

    @Test
    @DisplayName("both languages know the third setting, for the chat message and for Jade")
    void bothLanguagesHaveAllThreeSettings() throws IOException {
        for (String language : List.of("zh_cn.json", "en_us.json")) {
            String json = readResource("lang", language);
            for (Mode mode : Mode.values()) {
                assertTrue(json.contains("\"jade.redstonecircuit.weather." + mode.key() + "\""),
                        language + " is missing the Jade name of " + mode.key());
                assertTrue(json.contains("\"message.redstonecircuit.weather." + mode.key() + "\""),
                        language + " is missing the right-click message of " + mode.key());
            }
        }
    }

    /**
     * Reads one of the mod's resources, failing loudly rather than quietly skipping the check.
     *
     * <p>Tries both layouts the harness may run from (the project directory and the repository root), the
     * way the other source-reading guards here do - see {@code HostBufferTallyTest#readMainSource}.
     */
    private static String readResource(String kind, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "resources", "assets", "redstonecircuit", kind, fileName);
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, absolute)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + " and " + absolute + ")");
    }
}

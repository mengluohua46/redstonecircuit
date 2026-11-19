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

    private static final List<String> MOUNTS = List.of("down", "up", "north", "south", "east", "west");

    @Test
    @DisplayName("the blockstate answers every mount, every weather and every answer, or the block would not draw")
    void theBlockstateCoversEveryState() throws IOException {
        String json = readResource("blockstates", "weather_detector.json");
        for (String mount : MOUNTS) {
            for (Mode mode : Mode.values()) {
                for (String active : List.of("true", "false")) {
                    String variant = "\"mount=" + mount + ",weather=" + mode.key() + ",active=" + active + "\"";
                    assertTrue(json.contains(variant),
                            "the blockstate has no variant for " + variant + ", which leaves the block with "
                                    + "no model in that state");
                }
            }
        }
        assertFalse(json.contains("rainy="),
                "the block has no two-valued 'rainy' variant, so a blockstate still listing it "
                        + "would never match anything");
    }

    @Test
    @DisplayName("every mount puts the plate on its own face: 底贴住那一面，杆朝外")
    void everyMountPutsThePlateOnItsFace() throws IOException {
        var variants = com.google.gson.JsonParser.parseString(readResource("blockstates", "weather_detector.json"))
                .getAsJsonObject().getAsJsonObject("variants");
        for (String mount : MOUNTS) {
            var variant = variants.getAsJsonObject("mount=" + mount + ",weather=clear,active=false");
            int x = variant.has("x") ? variant.get("x").getAsInt() : 0;
            int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
            // 原版口径：方块状态的 x / y 合起来是 Ry(-y)·Rx(-x)
            int[][] m = mul(rotY(-y), rotX(-x));
            assertEquals(mount, name(apply(m, new int[] { 0, -1, 0 })),
                    mount + "：模型的底没有贴在挂载那一面");
            assertEquals(OPPOSITE.get(mount), name(apply(m, new int[] { 0, 1, 0 })),
                    mount + "：顶面和杆没有朝外");
        }
    }

    private static final java.util.Map<String, String> OPPOSITE = java.util.Map.of(
            "down", "up", "up", "down", "north", "south", "south", "north", "west", "east", "east", "west");

    private static final java.util.Map<String, int[]> DIR = java.util.Map.of(
            "down", new int[] { 0, -1, 0 }, "up", new int[] { 0, 1, 0 },
            "north", new int[] { 0, 0, -1 }, "south", new int[] { 0, 0, 1 },
            "west", new int[] { -1, 0, 0 }, "east", new int[] { 1, 0, 0 });

    private static String name(int[] v) {
        for (var entry : DIR.entrySet()) {
            int[] d = entry.getValue();
            if (d[0] == v[0] && d[1] == v[1] && d[2] == v[2]) {
                return entry.getKey();
            }
        }
        throw new AssertionError("不是一个方向：" + v[0] + "," + v[1] + "," + v[2]);
    }

    private static int[][] mul(int[][] a, int[][] b) {
        int[][] out = new int[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    out[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return out;
    }

    private static int[] apply(int[][] m, int[] v) {
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            for (int k = 0; k < 3; k++) {
                out[i] += m[i][k] * v[k];
            }
        }
        return out;
    }

    private static int[][] rotX(double degrees) {
        int s = (int) Math.round(Math.sin(Math.toRadians(degrees)));
        int c = (int) Math.round(Math.cos(Math.toRadians(degrees)));
        return new int[][] { { 1, 0, 0 }, { 0, c, -s }, { 0, s, c } };
    }

    private static int[][] rotY(double degrees) {
        int s = (int) Math.round(Math.sin(Math.toRadians(degrees)));
        int c = (int) Math.round(Math.cos(Math.toRadians(degrees)));
        return new int[][] { { c, 0, s }, { 0, 1, 0 }, { -s, 0, c } };
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
     * <p>Addressed from this project's own directory, so a guard that stops finding its file fails
     * loudly instead of reading another module's copy.
     */
    private static String readResource(String kind, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "resources", "assets", "redstonecircuit", kind, fileName);
        if (Files.isRegularFile(relative)) {
            return Files.readString(relative, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + ")");
    }
}

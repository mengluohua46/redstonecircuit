package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the one resource nothing else here can check: the model a locked repeater is drawn from.
 *
 * <p>A block model is only ever read by a client, so a blockstate naming a model that does not exist, a model
 * naming a texture that does not exist, or a {@code locked=true} variant still pointing at the unlocked model
 * all fail <em>silently</em>: the game starts, the server is happy, and the lock bars simply are not there.
 * That is exactly the defect this file exists for - every locked variant of this mod's three repeaters used
 * to point back at the plain model - so it is checked here, against the files on disk.
 */
class RepeaterLockAssetTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Path ASSETS = RESOURCES.resolve("assets");
    private static final String NAMESPACE = "redstonecircuit:";

    /** The three repeaters this mod draws itself; vanilla's own repeater is handled by vanilla. */
    private static final List<String> REPEATERS = List.of(
            "retaining_repeater", "double_repeater", "double_retaining_repeater");

    /**
     * The vanilla textures our locked models are allowed to name. The lock bar itself is vanilla's
     * {@code block/bedrock}, which is the one thing a locked repeater borrows rather than draws.
     */
    private static final Set<String> VANILLA_TEXTURES = Set.of(
            "block/redstone_torch_off", "block/redstone_torch", "block/bedrock", "minecraft:block/smooth_stone");

    private static JsonObject json(Path path) {
        assertTrue(Files.isRegularFile(path), "missing resource: " + path.toAbsolutePath());
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            assertTrue(parsed.isJsonObject(), path + " should hold a JSON object");
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException failed) {
            throw new AssertionError(path + " is not readable JSON: " + failed, failed);
        }
    }

    private static Path modelFile(String id) {
        String path = id.startsWith(NAMESPACE) ? id.substring(NAMESPACE.length()) : id;
        return ASSETS.resolve("redstonecircuit/models/" + path + ".json");
    }

    /** Every variant of one repeater's blockstate, as {@code variant -> model id}. */
    private static Map<String, String> variants(String repeater) {
        JsonObject file = json(ASSETS.resolve("redstonecircuit/blockstates/" + repeater + ".json"));
        JsonObject variants = file.getAsJsonObject("variants");
        assertFalse(variants == null || variants.isEmpty(), repeater + " should declare its variants");
        Map<String, String> byVariant = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            byVariant.put(entry.getKey(), entry.getValue().getAsJsonObject().get("model").getAsString());
        }
        return byVariant;
    }

    @Test
    @DisplayName("every variant of the three repeaters names a model that is on disk")
    void everyVariantModelExists() {
        int checked = 0;
        Set<String> missing = new TreeSet<>();
        for (String repeater : REPEATERS) {
            for (Map.Entry<String, String> entry : variants(repeater).entrySet()) {
                checked++;
                Path model = modelFile(entry.getValue());
                if (!Files.isRegularFile(model)) {
                    missing.add(repeater + " : " + entry.getKey() + " -> " + entry.getValue());
                }
            }
        }
        assertTrue(missing.isEmpty(), "variants naming a model that does not exist: " + missing);
        // Four delays x four facings x two powered states x two locked states, for each repeater.
        assertTrue(checked == 64 * REPEATERS.size(), "expected 64 variants per repeater, saw " + checked);
    }

    @Test
    @DisplayName("a locked variant never falls back to the unlocked model")
    void lockedVariantsUseALockedModel() {
        Set<String> wrong = new TreeSet<>();
        int locked = 0;
        for (String repeater : REPEATERS) {
            for (Map.Entry<String, String> entry : variants(repeater).entrySet()) {
                if (!entry.getKey().contains("locked=true")) {
                    continue;
                }
                locked++;
                if (!entry.getValue().endsWith("_locked")) {
                    wrong.add(repeater + " : " + entry.getKey() + " -> " + entry.getValue());
                }
            }
        }
        assertTrue(wrong.isEmpty(),
                "these lock and draw nothing, which is the defect this test exists for: " + wrong);
        // Four delays x two powered states x four facings, for each of the three repeaters.
        assertTrue(locked == 32 * REPEATERS.size(),
                "expected 32 locked variants per repeater, saw " + locked);
    }

    @Test
    @DisplayName("every texture a locked model names exists, and the lock bar is vanilla's")
    void lockedModelTexturesExist() {
        List<String> problems = new ArrayList<>();
        int models = 0;
        for (String repeater : REPEATERS) {
            for (int delay = 1; delay <= 4; delay++) {
                for (String on : List.of("", "_on")) {
                    String name = repeater + "_" + delay + "tick" + on + "_locked";
                    JsonObject model = json(modelFile(NAMESPACE + "block/" + name));
                    models++;
                    JsonObject textures = model.getAsJsonObject("textures");
                    assertFalse(textures == null, name + " should declare its textures");
                    for (Map.Entry<String, JsonElement> texture : textures.entrySet()) {
                        String value = texture.getValue().getAsString();
                        if (value.startsWith(NAMESPACE)) {
                            Path png = ASSETS.resolve("redstonecircuit/textures/" + value.substring(NAMESPACE.length()) + ".png");
                            if (!Files.isRegularFile(png)) {
                                problems.add(name + " : #" + texture.getKey() + " -> " + value);
                            }
                        } else if (!VANILLA_TEXTURES.contains(value)) {
                            problems.add(name + " : #" + texture.getKey() + " names an unexpected vanilla texture "
                                    + value);
                        }
                    }
                    if (!textures.has("lock") || !"block/bedrock".equals(textures.get("lock").getAsString())) {
                        problems.add(name + " does not draw vanilla's lock bar (expected #lock = block/bedrock)");
                    }
                    assertFalse(model.getAsJsonArray("elements") == null || model.getAsJsonArray("elements").isEmpty(),
                            name + " should have geometry to draw");
                }
            }
        }
        assertTrue(problems.isEmpty(), "locked models with a resource problem: " + problems);
        assertTrue(models == 24, "three repeaters x four delays x off/on should be 24 models, saw " + models);
    }

    @Test
    @DisplayName("the lock bar is vanilla's own element, in the place vanilla puts it")
    void lockBarIsVanillas() {
        // Vanilla widens the bar by two pixels per delay step: 6..8, 8..10, 10..12, 12..14.
        Pattern bar = Pattern.compile("\"from\":\\s*\\[\\s*2,\\s*2,\\s*(\\d+)\\s*\\],\\s*\"to\":\\s*\\[\\s*14,\\s*4,\\s*(\\d+)\\s*\\]");
        for (String repeater : REPEATERS) {
            for (int delay = 1; delay <= 4; delay++) {
                for (String on : List.of("", "_on")) {
                String name = repeater + "_" + delay + "tick" + on + "_locked";
                String text;
                Path path = modelFile(NAMESPACE + "block/" + name);
                try {
                    text = Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException failed) {
                    throw new AssertionError("could not read " + path, failed);
                }
                Matcher matcher = bar.matcher(text.replaceAll("\\s+", " "));
                assertTrue(matcher.find(), name + " should carry vanilla's 12x2x2 lock bar");
                int from = Integer.parseInt(matcher.group(1));
                int to = Integer.parseInt(matcher.group(2));
                assertTrue(from == 4 + 2 * delay && to == 6 + 2 * delay,
                        name + " puts the bar at z " + from + ".." + to + ", vanilla puts it at z "
                                + (4 + 2 * delay) + ".." + (6 + 2 * delay) + " for that delay");
                }
            }
        }
    }

    @Test
    @DisplayName("the three repeaters are the only ones affected, and they were all touched")
    void exactlyTheThreeRepeaters() {
        Set<String> withLockedModel = new LinkedHashSet<>();
        for (String repeater : REPEATERS) {
            for (Map.Entry<String, String> entry : variants(repeater).entrySet()) {
                if (entry.getKey().contains("locked=true") && entry.getValue().endsWith("_locked")) {
                    withLockedModel.add(repeater);
                }
            }
        }
        assertTrue(withLockedModel.equals(new LinkedHashSet<>(REPEATERS)),
                "all three self-drawn repeaters should use a locked model, saw " + withLockedModel);
    }
}

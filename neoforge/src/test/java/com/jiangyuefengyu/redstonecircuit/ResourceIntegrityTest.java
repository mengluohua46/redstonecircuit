package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Checks the mod's own resource files, which nothing else here can check.
 *
 * <h2>Why this exists</h2>
 * Everything else in the suite runs on a dedicated server, and a server never reads a block model, an
 * item model or a texture reference. Those files fail silently: a model that names a texture that is not
 * there, a blockstate that names a model that does not exist, or a model that forgets its render type -
 * the last of which made a placed superconducting wire render in the solid layer, where the dust art's
 * transparent pixels come out black, and cost a round of "the texture is all black" to find.
 *
 * <p>So the resources are read straight off disk and cross-checked against each other: every JSON file
 * parses, every {@code redstonecircuit:} model and texture reference resolves to a file that exists, and
 * the wire - the one block here whose art has transparency - is drawn in a cutout layer.
 *
 * <p>This is not a substitute for looking at the game; it is the part of that check that can be done
 * without one.
 */
class ResourceIntegrityTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final String ASSETS = "/assets/redstonecircuit/";

    private static List<Path> filesWithSuffix(String suffix) {
        assertTrue(Files.isDirectory(RESOURCES),
                "the mod's resources should be at " + RESOURCES.toAbsolutePath());
        try (Stream<Path> walk = Files.walk(RESOURCES)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException failed) {
            throw new AssertionError("could not walk " + RESOURCES, failed);
        }
    }

    private static JsonObject json(Path path) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            assertTrue(parsed.isJsonObject(), path + " should hold a JSON object");
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException failed) {
            throw new AssertionError(path + " is not readable JSON: " + failed, failed);
        }
    }

    @Test
    @DisplayName("every JSON resource the mod ships is valid, and there are some")
    void everyJsonParses() {
        List<Path> files = filesWithSuffix(".json");
        assertFalse(files.isEmpty(), "the mod should ship JSON resources");

        List<String> names = new ArrayList<>();
        for (Path path : files) {
            json(path);
            // Normalised, because the separator a path uses is the platform's business and the names
            // below are the ones a reader sees in the repository.
            names.add(RESOURCES.relativize(path).toString().replace('\\', '/'));
        }
        // The files this test exists for: if one of them were dropped the check would silently shrink.
        for (String expected : List.of(
                "assets/redstonecircuit/blockstates/superconducting_redstone.json",
                "assets/redstonecircuit/models/item/superconducting_redstone.json",
                "assets/redstonecircuit/models/item/redstone_goggles.json",
                "assets/redstonecircuit/models/item/redstone_wrench.json",
                "assets/redstonecircuit/lang/zh_cn.json",
                "assets/redstonecircuit/lang/en_us.json",
                "data/redstonecircuit/recipe/superconducting_redstone.json",
                "data/redstonecircuit/loot_table/blocks/superconducting_redstone.json")) {
            assertTrue(names.contains(expected), expected + " should exist, found " + names);
        }
    }

    @Test
    @DisplayName("every model, parent and texture the mod names actually exists")
    void referencesResolve() {
        List<Path> checked = new ArrayList<>();
        for (Path path : filesWithSuffix(".json")) {
            String relative = RESOURCES.relativize(path).toString().replace('\\', '/');
            if (!relative.startsWith("assets/")) {
                continue;
            }
            collectReferences(json(path), (key, value) -> {
                if (!value.startsWith(RedstoneCircuit.MODID + ":")) {
                    return;
                }
                String id = value.substring(RedstoneCircuit.MODID.length() + 1);
                Path target;
                if (key.equals("model") || key.equals("parent")) {
                    // A model reference is <namespace>:block/<name>, and the file carries a .json.
                    target = RESOURCES.resolve("assets/" + RedstoneCircuit.MODID + "/models/"
                            + id + ".json");
                } else {
                    // A texture reference is <namespace>:block/<name>, and the file carries a .png.
                    target = RESOURCES.resolve("assets/" + RedstoneCircuit.MODID + "/textures/"
                            + id + ".png");
                }
                assertTrue(Files.isRegularFile(target),
                        relative + " refers to " + value + ", which is not at "
                                + RESOURCES.relativize(target));
                checked.add(target);
            });
        }
        assertFalse(checked.isEmpty(), "the mod's assets should refer to each other somewhere");
    }

    /**
     * The bug this test was written for: a block whose art has transparent pixels and no render type is
     * drawn solid, so its transparent pixels are black.
     */
    @Test
    @DisplayName("the placed wire is drawn with a cutout render type")
    void theWireDeclaresACutoutLayer() {
        JsonObject blockstate = json(RESOURCES.resolve(
                "assets/redstonecircuit/blockstates/superconducting_redstone.json"));

        List<String> models = new ArrayList<>();
        collectReferences(blockstate, (key, value) -> {
            if (key.equals("model") && value.startsWith(RedstoneCircuit.MODID + ":")) {
                models.add(value.substring(RedstoneCircuit.MODID.length() + 1));
            }
        });
        assertFalse(models.isEmpty(), "the wire's blockstate should name some models");

        for (String model : models) {
            JsonObject definition = json(RESOURCES.resolve(
                    "assets/redstonecircuit/models/" + model + ".json"));
            JsonElement renderType = definition.get("render_type");
            assertTrue(renderType != null,
                    model + " needs a render_type: vanilla gives its own wire one through a table this "
                            + "block cannot join, and without it the dust art is drawn solid, which "
                            + "turns every transparent pixel black");
            assertTrue(renderType.getAsString().contains("cutout"),
                    model + " should be cutout, not " + renderType.getAsString());
        }
    }

    /** Every string value in a JSON tree, with the key it sat under. */
    private static void collectReferences(JsonElement element, ReferenceVisitor visitor) {
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonPrimitive() && entry.getValue().getAsJsonPrimitive().isString()) {
                    visitor.visit(entry.getKey(), entry.getValue().getAsString());
                } else {
                    collectReferences(entry.getValue(), visitor);
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectReferences(child, visitor);
            }
        }
    }

    @FunctionalInterface
    private interface ReferenceVisitor {
        void visit(String key, String value);
    }
}

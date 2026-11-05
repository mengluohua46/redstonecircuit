package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Checks that every key {@code RCConfig} defines has a name and a tooltip in both language files.
 *
 * <p>NeoForge's configuration screen has no fallback of its own for a missing name: it shows
 * {@code <modId>.configuration.<key>} verbatim, and for the tooltip it shows the English comment written in
 * the source. So a key nobody translated is not a cosmetic problem in one language - it is a raw key in
 * the Chinese screen, and English prose in the Chinese tooltip. Nothing else in the suite can see that,
 * because a configuration screen is only ever built by a client.
 *
 * <p>The keys are read out of the source rather than listed here, so adding a {@code define(...)} without
 * translating it fails this test instead of shipping: that is the whole point of it.
 */
class ConfigurationLangTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Path SOURCE = Path.of("src", "main", "java", "com", "jiangyuefengyu",
            "redstonecircuit", "RCConfig.java");
    private static final String PREFIX = "redstonecircuit.configuration.";

    /**
     * Every configuration key this build defines.
     *
     * <p>Read out of the {@code DEFAULTS} table rather than out of a builder chain: on this loader the
     * configuration is a hand-written JSON file, so {@code DEFAULTS.put(key, value)} is the one place a key is
     * declared. Reading it out of the source is what makes "added a setting, forgot the two language entries" fail
     * here instead of shipping.
     */
    private static final Pattern DEFAULT = Pattern.compile("DEFAULTS\\.put\\(\\s*\"([A-Za-z0-9_]+)\"");

    private static Set<String> definedKeys() {
        assertTrue(Files.isRegularFile(SOURCE), "RCConfig should be at " + SOURCE.toAbsolutePath());
        String source;
        try {
            source = Files.readString(SOURCE, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new AssertionError("could not read " + SOURCE, failed);
        }
        Set<String> keys = new LinkedHashSet<>();
        Matcher matcher = DEFAULT.matcher(source);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
        assertTrue(keys.size() > 40,
                "the pattern should find the whole configuration - found only " + keys.size() + " keys");
        return keys;
    }

    private static JsonObject lang(String file) {
        Path path = RESOURCES.resolve("assets/redstonecircuit/lang/" + file);
        assertTrue(Files.isRegularFile(path), "the language file should be at " + path.toAbsolutePath());
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            assertTrue(parsed.isJsonObject(), path + " should hold a JSON object");
            return parsed.getAsJsonObject();
        } catch (IOException | RuntimeException failed) {
            throw new AssertionError(path + " is not readable JSON: " + failed, failed);
        }
    }

    @Test
    @DisplayName("every configuration key has a name and a tooltip in both languages")
    void everyKeyIsTranslated() {
        Set<String> keys = definedKeys();
        for (String file : List.of("en_us.json", "zh_cn.json")) {
            JsonObject lang = lang(file);
            Set<String> missing = new TreeSet<>();
            for (String key : keys) {
                if (!lang.has(PREFIX + key)) {
                    missing.add(key);
                }
                if (!lang.has(PREFIX + key + ".tooltip")) {
                    missing.add(key + ".tooltip");
                }
            }
            assertTrue(missing.isEmpty(), file + " has no entry for: " + missing
                    + " - the configuration screen would show the raw key, or English prose, there");
        }
    }

    @Test
    @DisplayName("both languages translate the same keys, so neither is a stale copy of the other")
    void bothLanguagesAgree() {
        Set<String> english = new TreeSet<>();
        for (Map.Entry<String, JsonElement> entry : lang("en_us.json").entrySet()) {
            if (entry.getKey().startsWith(PREFIX)) {
                english.add(entry.getKey());
            }
        }
        Set<String> chinese = new TreeSet<>();
        for (Map.Entry<String, JsonElement> entry : lang("zh_cn.json").entrySet()) {
            if (entry.getKey().startsWith(PREFIX)) {
                chinese.add(entry.getKey());
            }
        }
        Set<String> onlyEnglish = new TreeSet<>(english);
        onlyEnglish.removeAll(chinese);
        Set<String> onlyChinese = new TreeSet<>(chinese);
        onlyChinese.removeAll(english);
        assertTrue(onlyEnglish.isEmpty(), "zh_cn.json is missing: " + onlyEnglish);
        assertTrue(onlyChinese.isEmpty(), "en_us.json is missing: " + onlyChinese);
        assertFalse(english.isEmpty(), "the configuration should be translated at all");
    }

    @Test
    @DisplayName("the overlay mode's three values are translated")
    void enumValuesAreTranslated() {
        // On this loader the screen that cycles the setting is ours, so the key convention is ours too:
        // "redstonecircuit.configuration.hostOverlayMode." + the constant name. What can go stale is the
        // language file, and that is what is checked - one key per constant, in both languages.
        for (String file : List.of("en_us.json", "zh_cn.json")) {
            JsonObject lang = lang(file);
            for (String value : List.of("GOGGLES", "ALWAYS", "OFF")) {
                String key = PREFIX + "hostOverlayMode." + value;
                assertTrue(lang.has(key), file + " has no entry for " + key
                        + " - the appearance mode would cycle through raw constant names");
            }
        }
    }

    @Test
    @DisplayName("no configuration key is left over from a setting that no longer exists")
    void noOrphanKeys() {
        Set<String> keys = definedKeys();
        for (String file : List.of("en_us.json", "zh_cn.json")) {
            Set<String> orphans = new TreeSet<>();
            for (Map.Entry<String, JsonElement> entry : lang(file).entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith(PREFIX)) {
                    continue;
                }
                String rest = key.substring(PREFIX.length());
                // The screen's own chrome: its title, and the per-file section headers.
                if (rest.equals("title") || rest.startsWith("section.")) {
                    continue;
                }
                String name = rest.endsWith(".tooltip")
                        ? rest.substring(0, rest.length() - ".tooltip".length())
                        : rest;
                // An enum setting also has one key per constant, and its constants are read out of the
                // enum rather than out of define(...), so they are not orphans: `hostOverlayMode.OFF` is a
                // value of `hostOverlayMode`, which is a key.
                int dot = name.lastIndexOf('.');
                if (dot > 0 && name.substring(dot + 1).matches("[A-Z][A-Z0-9_]*")
                        && keys.contains(name.substring(0, dot))) {
                    continue;
                }
                if (!keys.contains(name)) {
                    orphans.add(key);
                }
            }
            assertTrue(orphans.isEmpty(), file + " translates settings RCConfig does not define: "
                    + orphans);
        }
    }
}

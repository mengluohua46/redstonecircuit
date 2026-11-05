package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sentinels for the one piece of this mod that renames itself per language.
 *
 * <p>FML has no translated mod name: the mod list draws {@code IModInfo#getDisplayName()} as a literal, so the
 * name is patched in the client - and a patch like that fails quietly in three different ways. It can stop
 * matching after a NeoForge update ({@code require = 0} then leaves the English name, which is also what the
 * toml says, so nothing looks broken), it can be written but never registered in the mixin config, and the
 * language key it reads can be missing, in which case the list would show the raw key. A unit test cannot
 * start a client, so it checks the three things that would go wrong: the source says what it must, the config
 * registers it, and both language files carry the key with a different value.
 */
class ModListNameMixinTest {

    private static final Path MIXIN = Path.of("src", "main", "java", "com", "jiangyuefengyu",
            "redstonecircuit", "mixin", "ModListNameMixin.java");
    private static final Path MIXIN_CONFIG = Path.of("src", "main", "resources",
            "redstonecircuit.mixins.json");
    private static final Path LANG = Path.of("src", "main", "resources",
            "assets", "redstonecircuit", "lang");
    private static final Path TOML = Path.of("src", "main", "templates", "META-INF", "neoforge.mods.toml");
    private static final String NAME_KEY = "redstonecircuit.mod.name";

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new AssertionError("could not read " + path.toAbsolutePath(), failed);
        }
    }

    /** The source with its comments removed, so the sentinels below cannot be satisfied by prose. */
    private static String code() {
        String source = read(MIXIN);
        source = source.replaceAll("(?s)/\\*.*?\\*/", " ");
        return source.replaceAll("//[^\\n]*", " ");
    }

    private static JsonObject lang(String file) {
        Path path = LANG.resolve(file);
        try {
            JsonElement parsed = JsonParser.parseString(read(path));
            assertTrue(parsed.isJsonObject(), path + " should hold a JSON object");
            return parsed.getAsJsonObject();
        } catch (RuntimeException failed) {
            throw new AssertionError(path + " is not readable JSON: " + failed, failed);
        }
    }

    @Test
    @DisplayName("the mixin renames this mod only, and reads its name from the language file")
    void onlyThisModIsRenamed() {
        String code = code();
        assertTrue(code.contains("\"redstonecircuit\".equals("),
                "the rename has to be guarded by the mod id, or it would rename every mod in the list");
        assertTrue(code.contains("I18n.exists(") && code.contains("I18n.get("),
                "the name has to come from the language file, and only while its key exists");
        assertTrue(code.contains(NAME_KEY), "the key the two language files carry is " + NAME_KEY);
        assertTrue(code.contains("getDisplayName"),
                "the patch redirects the name lookup itself");
        assertTrue(code.contains("require = 0"),
                "a NeoForge reshape should leave the English name showing, not crash the client");
    }

    @Test
    @DisplayName("the mixin changes drawing only: no search, no sorting, no list rebuild")
    void searchAndSortAreLeftAlone() {
        String code = code().toLowerCase(java.util.Locale.ROOT);
        List<String> forbidden = new ArrayList<>();
        for (String token : List.of("getsearchable", "setfilter", "sorttype", "sort(", "buildmodlist",
                "refreshlist", "getnarration")) {
            if (code.contains(token)) {
                forbidden.add(token);
            }
        }
        assertTrue(forbidden.isEmpty(),
                "the patch must not touch the search or the ordering, it touches drawing only: saw " + forbidden);
    }

    @Test
    @DisplayName("the mixin is registered in the client half of the mixin config")
    void mixinIsRegistered() {
        JsonObject config = null;
        try {
            JsonElement parsed = JsonParser.parseString(read(MIXIN_CONFIG));
            assertTrue(parsed.isJsonObject(), MIXIN_CONFIG + " should hold a JSON object");
            config = parsed.getAsJsonObject();
        } catch (RuntimeException failed) {
            throw new AssertionError(MIXIN_CONFIG + " is not readable JSON: " + failed, failed);
        }
        JsonElement client = config.get("client");
        assertTrue(client != null && client.isJsonArray(), "the config should list its client mixins");
        boolean listed = false;
        for (JsonElement entry : client.getAsJsonArray()) {
            if ("ModListNameMixin".equals(entry.getAsString())) {
                listed = true;
            }
        }
        assertTrue(listed, "ModListNameMixin is not in the client list, so nothing would ever apply it");
    }

    @Test
    @DisplayName("both languages name the mod, and the Chinese one is actually Chinese")
    void bothLanguagesNameTheMod() {
        String english = lang("en_us.json").has(NAME_KEY)
                ? lang("en_us.json").get(NAME_KEY).getAsString()
                : null;
        String chinese = lang("zh_cn.json").has(NAME_KEY)
                ? lang("zh_cn.json").get(NAME_KEY).getAsString()
                : null;
        assertTrue(english != null, "en_us.json has no " + NAME_KEY + " - the mod list would show the raw key");
        assertTrue(chinese != null, "zh_cn.json has no " + NAME_KEY + " - the mod list would show the raw key");
        assertEquals("Redstone Circuit", english, "the English name is the one the toml carries");
        assertTrue(read(TOML).contains("displayName=\"Redstone Circuit\""),
                "the toml is what every other screen draws (logs, crash reports, the info panel), so the "
                        + "English language entry and the toml name have to agree");
        assertFalse(chinese.equals(english), "the Chinese entry should differ from the English one");
        assertTrue(chinese.codePoints().anyMatch(cp -> cp > 0x7F),
                "the Chinese entry should contain Chinese, not a copy of the English name");
    }
}

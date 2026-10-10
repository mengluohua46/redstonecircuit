package com.jiangyuefengyu.redstonecircuit.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The Fabric side's replacement for NeoForge's {@code ModConfigSpec}: one JSON file, no dependencies.
 *
 * <h2>Why hand-written</h2>
 * Fabric has no configuration system of its own, and the alternatives (Cloth Config and friends) would
 * turn an optional convenience into something every player has to install. The mod's options are a flat
 * list of numbers and flags, so a JSON object read once at startup is enough - and the file is written
 * with every key it knows about on first run, which doubles as the documentation a player would
 * otherwise get from a config screen.
 *
 * <h2>How it is used</h2>
 * The ported {@code RCConfig} keeps its existing getter names and reads through this class, so nothing
 * else in the mod has to know which loader it is running on:
 *
 * <pre>{@code
 * private static final JsonConfig CONFIG = JsonConfig.load(
 *         FabricLoader.getInstance().getConfigDir().resolve("redstonecircuit.json"),
 *         defaults);
 *
 * public static boolean showInnerComponents() {
 *     return CONFIG.getBoolean("showInnerComponents", true);
 * }
 * }</pre>
 *
 * <p>Unknown keys in the file are preserved when it is rewritten, so a newer version's options are not
 * silently dropped by an older one.
 */
public final class JsonConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private final JsonObject values;

    private JsonConfig(Path path, JsonObject values) {
        this.path = path;
        this.values = values;
    }

    /**
     * Reads the file at {@code path}, creating it from {@code defaults} when it is missing or broken.
     *
     * <p>A file that cannot be parsed is replaced rather than repaired: a half-read configuration is
     * worse than a fresh one, and the old file is kept next to it as {@code .bak} so nothing is lost.
     */
    public static JsonConfig load(Path path, Map<String, Object> defaults) {
        JsonObject fallback = new JsonObject();
        for (Map.Entry<String, Object> entry : new TreeMap<>(defaults).entrySet()) {
            fallback.add(entry.getKey(), toJson(entry.getValue()));
        }
        JsonObject loaded = null;
        if (Files.isRegularFile(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (parsed.isJsonObject()) {
                    loaded = parsed.getAsJsonObject();
                }
            } catch (IOException | RuntimeException broken) {
                loaded = null;
            }
            if (loaded == null) {
                try {
                    Files.move(path, path.resolveSibling(path.getFileName() + ".bak"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // Nothing to do about it: the defaults still work.
                }
            }
        }
        JsonConfig config = new JsonConfig(path, loaded != null ? loaded : new JsonObject());
        // Fill in anything the file does not have yet (a first run, or a new option), then write it all
        // back so the file on disk always lists every option.
        for (Map.Entry<String, JsonElement> entry : fallback.entrySet()) {
            config.values.asMap().putIfAbsent(entry.getKey(), entry.getValue());
        }
        config.save();
        return config;
    }

    public int getInt(String key, int fallback) {
        return read(key, fallback).getAsInt();
    }

    public float getFloat(String key, float fallback) {
        return read(key, fallback).getAsFloat();
    }

    public boolean getBoolean(String key, boolean fallback) {
        return read(key, fallback).getAsBoolean();
    }

    public String getString(String key, String fallback) {
        return read(key, fallback).getAsString();
    }

    /**
     * A list of strings, for an option that names things rather than measuring them (the block ids a
     * capture may not touch).
     *
     * <p>Every entry is read as a string, so a hand-edited file that holds numbers still answers with
     * something usable instead of failing the option.
     */
    public java.util.List<String> getStringList(String key, java.util.List<String> fallback) {
        JsonElement value = read(key, fallback);
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (value.isJsonArray()) {
            for (JsonElement entry : value.getAsJsonArray()) {
                if (entry != null && !entry.isJsonNull()) {
                    lines.add(entry.getAsString());
                }
            }
        }
        return java.util.List.copyOf(lines);
    }

    public Path path() {
        return path;
    }

    /** Writes the current values out. Failures are swallowed: a read-only config must not stop the game. */
    public void save() {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(values, writer);
            }
        } catch (IOException | RuntimeException ignored) {
            // Playing with the defaults beats refusing to start over a config file.
        }
    }

    /**
     * The value at {@code key}, or the fallback when it is missing or of the wrong shape.
     *
     * <p>The fallback is also written back, so a hand-edited file that lost a value (or has a string
     * where a number belongs) is repaired on the next save instead of failing every read.
     */
    private JsonElement read(String key, Object fallback) {
        JsonElement value = values.get(key);
        if (value == null || value.isJsonNull() || !isSameKind(value, fallback)) {
            value = toJson(fallback);
            values.add(key, value);
            save();
        }
        return value;
    }

    private static boolean isSameKind(JsonElement value, Object fallback) {
        if (fallback instanceof Number) {
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
        }
        if (fallback instanceof Boolean) {
            return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
        }
        if (fallback instanceof java.util.Collection<?>) {
            return value.isJsonArray();
        }
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
    }

    private static JsonElement toJson(Object value) {
        if (value instanceof Number number) {
            return new com.google.gson.JsonPrimitive(number);
        }
        if (value instanceof Boolean flag) {
            return new com.google.gson.JsonPrimitive(flag);
        }
        if (value instanceof java.util.Collection<?> entries) {
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            for (Object entry : entries) {
                array.add(String.valueOf(entry));
            }
            return array;
        }
        return new com.google.gson.JsonPrimitive(String.valueOf(value));
    }
}

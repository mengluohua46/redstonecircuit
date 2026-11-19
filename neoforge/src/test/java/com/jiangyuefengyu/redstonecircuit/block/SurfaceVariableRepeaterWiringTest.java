package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 维持 / 双倍 / 双倍维持 中继器（贴面）的方块状态与模型：**每一张被引用的模型都真的存在，而且用的是这一族的贴图**。
 *
 * <p>这一族是三族孪生里唯一靠生成器铺开的（每个墙面挂载 × 每个朝向 × 每一档都由一张模型负责，256 张一族），
 * 生成器写错一个名字就是紫黑块，而紫黑块只有进游戏才看得见 —— 所以名字与文件是否存在，只能在这里钉住。
 */
class SurfaceVariableRepeaterWiringTest {

    /** kind -> the two textures its own floor models use. */
    private static final Map<String, String[]> KINDS = Map.of(
            "retaining", new String[] { "redstonecircuit:block/retaining_repeater_top",
                    "redstonecircuit:block/retaining_repeater_base" },
            "double", new String[] { "redstonecircuit:block/double_repeater_top",
                    "minecraft:block/smooth_stone" },
            "double_retaining", new String[] { "redstonecircuit:block/double_retaining_repeater_top",
                    "redstonecircuit:block/retaining_repeater_base" });

    /**
     * How many torches a model draws, counted by their bodies: every torch is the same little 2×5×2 box, whichever
     * way the model has been turned, so the three dimensions sorted are the fingerprint.
     */
    private static int torches(JsonObject model) {
        int count = 0;
        for (JsonElement element : model.getAsJsonArray("elements")) {
            JsonObject body = element.getAsJsonObject();
            double[] size = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                size[axis] = Math.abs(body.getAsJsonArray("to").get(axis).getAsDouble()
                        - body.getAsJsonArray("from").get(axis).getAsDouble());
            }
            java.util.Arrays.sort(size);
            if (Math.abs(size[0] - 2.0) < 0.01 && Math.abs(size[1] - 5.0) < 0.01
                    && Math.abs(size[2] - 2.0) < 0.01) {
                count++;
            }
        }
        return count;
    }

    /** The rotation of the one face that carries the top texture - the face the player sees on a wall. */
    private static int topRotation(JsonObject model) {
        int found = -1;
        for (JsonElement element : model.getAsJsonArray("elements")) {
            JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
            if (faces == null) {
                continue;
            }
            for (Map.Entry<String, JsonElement> face : faces.entrySet()) {
                if ("#top".equals(face.getValue().getAsJsonObject().get("texture").getAsString())) {
                    assertEquals(-1, found, "一张模型里不该有两个 #top 面");
                    JsonElement rotation = face.getValue().getAsJsonObject().get("rotation");
                    found = rotation == null ? 0 : rotation.getAsInt();
                }
            }
        }
        assertTrue(found >= 0, "这张模型里没有 #top 面");
        return found;
    }

    /** The same textures, key order ignored: the generator writes them in whatever order it met them. */
    private static String sorted(JsonObject textures) {
        java.util.TreeMap<String, String> entries = new java.util.TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : textures.entrySet()) {
            entries.put(entry.getKey(), entry.getValue().getAsString());
        }
        return entries.toString();
    }

    private static Path models() {
        return Path.of("src", "main", "resources", "assets", "redstonecircuit", "models", "block");
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Path modelFile(String reference) {
        String path = reference.substring(reference.indexOf(':') + 1);
        return models().resolve((path.startsWith("block/") ? path.substring("block/".length()) : path) + ".json");
    }

    @Test
    @DisplayName("every variant of all three blockstates names a model that exists")
    void everyVariantNamesAModelThatExists() throws IOException {
        for (String kind : KINDS.keySet()) {
            Path blockstate = Path.of("src", "main", "resources", "assets", "redstonecircuit", "blockstates",
                    "surface_" + kind + "_repeater.json");
            JsonObject variants = json(blockstate).getAsJsonObject("variants");
            assertTrue(variants.size() == 576, kind + " 该有 576 个变体（6 挂载 × 6 朝向 × 4 档 × 2 锁 × 2 通），实际 "
                    + variants.size());
            List<String> missing = new ArrayList<>();
            for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
                String reference = entry.getValue().getAsJsonObject().get("model").getAsString();
                if (!reference.startsWith("redstonecircuit:")) {
                    continue;
                }
                if (!Files.exists(modelFile(reference))) {
                    missing.add(entry.getKey() + " -> " + reference);
                }
            }
            assertTrue(missing.isEmpty(), kind + " 有 " + missing.size() + " 个变体指向不存在的模型，头几个："
                    + missing.subList(0, Math.min(3, missing.size())));
        }
    }

    @Test
    @DisplayName("a wall mount paints exactly what this family paints on the floor, state by state")
    void aWallMountUsesThisFamilysTextures() throws IOException {
        // 逐状态比对，而不是只比对关闭状态：双倍两族**通电换一张顶面贴图**（_top_on），
        // 生成器按状态抄错一次，上墙之后通电也画关闭的样子，看着就是「火把用的是普通中继器那套」
        //（作者 2026-11-15）。
        for (String kind : KINDS.keySet()) {
            for (int delay = 1; delay <= 4; delay++) {
                for (String variant : List.of("", "_on", "_locked", "_on_locked")) {
                    String state = delay + "tick" + variant;
                    JsonObject flat = json(models().resolve(kind + "_repeater_" + state + ".json"));
                    for (String mount : List.of("east", "west", "north", "south")) {
                        String model = "surface_" + kind + "_repeater_" + mount + "_up_repeater_" + state + ".json";
                        String geometry = "surface_repeater_" + mount + "_up_repeater_" + state;
                        JsonObject child = json(models().resolve(model));
                        assertEquals(sorted(flat.getAsJsonObject("textures")),
                                sorted(child.getAsJsonObject("textures")),
                                model + " 的贴图必须与地面上那一族同状态的一模一样");
                        // 墙挂模型必须**画该族自己的火把**：两族双倍本体是四支（x 方向两对），中继器（贴面）的几何
                        // 只有两支 —— 作者 2026-11-15「贴墙的两个双倍中继器上面的红石火把还是用的和普通中继器一样」。
                        assertEquals(torches(flat), torches(child),
                                model + " 的火把数必须跟地面上那一族同状态的一样");
                        // 维持那族朝外那一面（唯一带 #top 的面）还要再转 180°：作者艺术里红条方向跟原版相反。
                        int parent = topRotation(json(models().resolve(geometry + ".json")));
                        int expected = "retaining".equals(kind) ? (parent + 180) % 360 : parent;
                        assertEquals(expected, topRotation(child),
                                model + " 朝外那面的贴图朝向不对");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the flat variants use this family's own floor models, not vanilla's")
    void theFlatVariantsUseThisFamilysOwnModels() throws IOException {
        for (String kind : KINDS.keySet()) {
            Path blockstate = Path.of("src", "main", "resources", "assets", "redstonecircuit", "blockstates",
                    "surface_" + kind + "_repeater.json");
            String text = Files.readString(blockstate, StandardCharsets.UTF_8);
            assertTrue(text.contains("\"redstonecircuit:block/" + kind + "_repeater_1tick\""),
                    kind + " 的平放变体该用自己那一族的模型");
            assertTrue(!text.contains("minecraft:block/repeater_"),
                    kind + " 还引用着原版中继器的模型，那会让这一族在平放时变成原版的样子");
            for (int delay = 1; delay <= 4; delay++) {
                for (String variant : List.of("", "_on", "_locked", "_on_locked")) {
                    assertTrue(Files.exists(models().resolve(kind + "_repeater_" + delay + "tick" + variant + ".json")),
                            kind + " 缺平放模型 " + delay + "tick" + variant);
                }
            }
        }
    }
}
package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 同步火把的方块状态表: 六个朝向一个都不许漏, 贴墙的旋转必须是"反过来的"那一套.
 *
 * <p>为什么这条要有测试: 一个漏掉的组合不报错、不写日志, 它只是被画成"缺失模型"(紫黑格) - 而最容易漏的恰恰是最常见的
 * 那一个。立在地上时 {@code FACING} 是"吸附面", 而吸附面朝下, 所以 {@code facing=down} 才是玩家最常看见的状态; 它漏掉
 * 的表现就是"放下之后贴图无效", 服务端一切正常。
 *
 * <p>旋转为什么要取反: 原版 {@code wall_torch} 的 {@code FACING} 指向<b>离墙</b>的方向, 而这里的 {@code FACING} 指向
 * <b>吸附面(墙)</b> - 同一堵墙在两个方块里叫相反的名字, 于是 {@code facing=east} 要用原版 {@code facing=west} 的旋转。
 * 四个数字写反了同样不会报错, 只会让贴墙的火把插到墙的另一侧。
 */
class SyncTorchAssetTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Path ASSETS = RESOURCES.resolve("assets").resolve("redstonecircuit");

    /** 每个朝向期望的 {@code y}: 地面/吊顶不转, 四个贴墙朝向按"取反"的那一套。 */
    private static final Map<String, Integer> WALL_ROTATION = new LinkedHashMap<>();

    static {
        WALL_ROTATION.put("east", 180);
        WALL_ROTATION.put("west", 0);
        WALL_ROTATION.put("north", 90);
        WALL_ROTATION.put("south", 270);
    }

    private static JsonObject json(Path path) {
        assertTrue(Files.isRegularFile(path), "missing resource: " + path.toAbsolutePath());
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            assertTrue(parsed.isJsonObject(), "not an object: " + path);
            return parsed.getAsJsonObject();
        } catch (IOException e) {
            throw new AssertionError("could not read " + path, e);
        }
    }

    @Test
    @DisplayName("六个朝向 × 亮灭两档全都在表里, 尤其是立在地上用的 facing=down")
    void everyFacingAndBothStatesAreDeclared() {
        JsonObject variants = json(ASSETS.resolve("blockstates").resolve("sync_torch.json"))
                .getAsJsonObject("variants");

        for (String facing : new String[] {"down", "up", "north", "south", "west", "east"}) {
            for (boolean lit : new boolean[] {false, true}) {
                String key = "facing=" + facing + ",lit=" + lit;
                assertTrue(variants.has(key), "方块状态表里缺 " + key + " - 那一档会被画成缺失模型(紫黑格), "
                        + "而实际有 " + variants.keySet());
            }
        }
        // 立在地上就是 facing=down: 它是玩家最常看见的那一档, 也是"放下之后贴图无效"那次真正漏掉的那一档。
        assertTrue(variants.has("facing=down,lit=false"), "放下的那一刻是灭的, 这一档必须存在");
    }

    @Test
    @DisplayName("贴墙的四个旋转是取反的那一套, 地面/吊顶不旋转")
    void wallRotationsAreTheMirroredSet() {
        JsonObject variants = json(ASSETS.resolve("blockstates").resolve("sync_torch.json"))
                .getAsJsonObject("variants");

        for (Map.Entry<String, Integer> entry : WALL_ROTATION.entrySet()) {
            for (boolean lit : new boolean[] {false, true}) {
                JsonObject variant = variants.getAsJsonObject("facing=" + entry.getKey() + ",lit=" + lit);
                int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
                assertEquals(entry.getValue().intValue(), y,
                        "facing=" + entry.getKey() + " 应当用 y=" + entry.getValue()
                                + ": 我们的 FACING 指向吸附面, 原版 wall_torch 的指向离墙方向");
            }
        }
        for (String facing : new String[] {"down", "up"}) {
            for (boolean lit : new boolean[] {false, true}) {
                JsonObject variant = variants.getAsJsonObject("facing=" + facing + ",lit=" + lit);
                assertEquals(0, variant.has("y") ? variant.get("y").getAsInt() : 0,
                        "facing=" + facing + " 是地面/吊顶, 不该绕 Y 转");
            }
        }
        // 吊在方块底面那一档要倒过来: 原版没有天花板火把的模型, 所以把地面模型绕 X 转 180°。方块内部"点顶面"放下的
        // 那一支 (slot.facing = UP) 走的正是这一档 —— 它原本与立着的那一支画得一模一样, 这就是那条断言存在的理由。
        for (boolean lit : new boolean[] {false, true}) {
            JsonObject up = variants.getAsJsonObject("facing=up,lit=" + lit);
            assertEquals(180, up.has("x") ? up.get("x").getAsInt() : 0,
                    "facing=up 是吊在方块底面, 模型要绕 X 转 180°");
            assertFalse(variants.getAsJsonObject("facing=down,lit=" + lit).has("x"),
                    "立在地上那一支不该绕 X 转");
        }
    }

    @Test
    @DisplayName("表里点名的每个模型、模型点名的贴图都在盘上, 亮的那一档用 _on")
    void everyNamedModelAndTextureExists() {
        JsonObject variants = json(ASSETS.resolve("blockstates").resolve("sync_torch.json"))
                .getAsJsonObject("variants");

        for (String key : variants.keySet()) {
            String model = variants.getAsJsonObject(key).get("model").getAsString();
            String path = model.substring("redstonecircuit:".length());
            Path file = ASSETS.resolve("models").resolve(path + ".json");
            assertTrue(Files.isRegularFile(file), key + " 指的模型不存在: " + model);
            assertTrue(key.contains("lit=true") == path.endsWith("_on"),
                    key + " 应当用" + (key.contains("lit=true") ? "亮起" : "熄灭") + "的那个模型, 实际 " + model);

            JsonObject textures = json(file).has("textures") ? json(file).getAsJsonObject("textures") : null;
            assertTrue(textures != null && textures.has("torch"), model + " 少了 torch 这个贴图变量");
            String texture = textures.get("torch").getAsString();
            Path png = ASSETS.resolve("textures").resolve(texture.substring("redstonecircuit:".length()) + ".png");
            assertTrue(Files.isRegularFile(png), model + " 指的贴图不存在: " + texture);
        }
    }

    @Test
    @DisplayName("物品模型就用方块模型: 手里的那一支和放下去的是同一个样子")
    void theItemModelPointsAtTheBlockModel() {
        JsonObject item = json(ASSETS.resolve("models").resolve("item").resolve("sync_torch.json"));
        assertEquals("redstonecircuit:block/sync_torch", item.get("parent").getAsString(),
                "物品模型应当以放下的那个模型为父, 否则手里和地上会是两个样子");
        JsonObject tuner = json(ASSETS.resolve("models").resolve("item").resolve("frequency_tuner.json"));
        String layer = tuner.getAsJsonObject("textures").get("layer0").getAsString();
        assertTrue(Files.isRegularFile(ASSETS.resolve("textures")
                        .resolve(layer.substring("redstonecircuit:".length()) + ".png")),
                "调频器的物品贴图不存在: " + layer);
    }
}

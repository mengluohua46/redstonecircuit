package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 红石触发器（贴面）的方块状态：**每个状态都有模型，底贴在挂载那一面，杠杆朝着它读的那一面**。
 *
 * <p>这里不抄表，把规格写成矩阵验：方块状态的 {@code x} / {@code y} 加上模型自带的平面内转身，合起来必须把模型的
 * 背面（贴住物体的那面）送到 {@code mount}，把杠杆（{@code facing=north} 时朝北的那根）送到 {@code facing}。
 *
 * <p>两条坐标约定都来自原版，不是自定的：
 * <ul>
 *   <li>{@code BlockModelRotation} 用 {@code rotateYXZ(-y, -x)}，即 M = Ry(-y)·Rx(-x)；</li>
 *   <li>模型元素的 {@code rotation.angle} 是右手绕轴（{@code FaceBakery} 里 {@code rotationAxis(+angle, axis)}）。</li>
 * </ul>
 * 所以参考模型（{@code trigger}）在 x=0,y=0 时读北、底朝下。
 */
class SurfaceTriggerWiringTest {

    private static final List<String> SIX = List.of("down", "up", "north", "south", "east", "west");

    /** 地面/吊顶沿用本体（与原版 lever 一致）的表：吊顶多 180°，因为 x=180 把平面内的手性翻了过来。 */
    private static final java.util.Map<String, Integer> FLOOR_Y = java.util.Map.of(
            "north", 0, "east", 90, "south", 180, "west", 270);
    private static final java.util.Map<String, Integer> CEILING_Y = java.util.Map.of(
            "north", 180, "east", 270, "south", 0, "west", 90);

    private static JsonObject variants() throws IOException {
        Path file = Path.of("src", "main", "resources", "assets", "redstonecircuit", "blockstates",
                "surface_trigger.json");
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                .getAsJsonObject("variants");
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

    private static String name(int[] v) {
        for (String s : SIX) {
            int[] d = dir(s);
            if (d[0] == v[0] && d[1] == v[1] && d[2] == v[2]) {
                return s;
            }
        }
        throw new AssertionError("不是一个方向：" + v[0] + "," + v[1] + "," + v[2]);
    }

    private static int[] dir(String facing) {
        return switch (facing) {
            case "down" -> new int[] { 0, -1, 0 };
            case "up" -> new int[] { 0, 1, 0 };
            case "north" -> new int[] { 0, 0, -1 };
            case "south" -> new int[] { 0, 0, 1 };
            case "west" -> new int[] { -1, 0, 0 };
            case "east" -> new int[] { 1, 0, 0 };
            default -> throw new AssertionError(facing);
        };
    }

    private static String opposite(String facing) {
        return switch (facing) {
            case "down" -> "up";
            case "up" -> "down";
            case "north" -> "south";
            case "south" -> "north";
            case "west" -> "east";
            case "east" -> "west";
            default -> throw new AssertionError(facing);
        };
    }

    /** 面外朝向：一个墙面挂不上朝墙里/朝墙外的方向，这类状态只在 /setblock 改造时出现，不参与规格核对。 */
    private static boolean alongMountAxis(String mount, String facing) {
        boolean vertical = mount.equals("down") || mount.equals("up");
        if (vertical) {
            return facing.equals("up") || facing.equals("down");
        }
        return facing.equals(mount) || facing.equals(opposite(mount));
    }

    private static JsonObject variant(JsonObject variants, String mount, String facing, boolean powered) {
        String key = "mount=" + mount + ",facing=" + facing + ",input=false,powered=" + powered;
        assertTrue(variants.has(key), "缺这个状态：" + key);
        return variants.getAsJsonObject(key);
    }

    private static int turnOf(JsonObject variant) {
        String model = variant.get("model").getAsString();
        int at = model.indexOf("_turn");
        return at < 0 ? 0 : Integer.parseInt(model.substring(at + "_turn".length()));
    }

    /** 方块状态的 x / y 加上模型自带的转身，合起来作用在参考模型上的那个矩阵。 */
    private static int[][] transform(JsonObject variant) {
        int x = variant.has("x") ? variant.get("x").getAsInt() : 0;
        int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
        return mul(rotY(-y), mul(rotX(-x), rotY(-90 * turnOf(variant))));
    }

    @Test
    @DisplayName("every state of the twin has a model: 6 mounts x 6 facings x input x powered")
    void everyStateHasAModel() throws IOException {
        JsonObject variants = variants();
        assertEquals(144, variants.size(), "状态空间 6 × 6 × 2 × 2 = 144");
        for (String mount : SIX) {
            for (String facing : SIX) {
                for (boolean powered : new boolean[] { false, true }) {
                    variant(variants, mount, facing, powered);
                }
            }
        }
    }

    @Test
    @DisplayName("the floor and the ceiling keep the flat form's own table")
    void theFlatMountsKeepTheFlatTable() throws IOException {
        JsonObject variants = variants();
        for (var entry : FLOOR_Y.entrySet()) {
            String facing = entry.getKey();
            JsonObject floor = variant(variants, "down", facing, false);
            assertEquals(entry.getValue(), floor.has("y") ? floor.get("y").getAsInt() : 0,
                    "贴地 facing=" + facing + " 的 y 不对");
            assertTrue(!floor.has("x"), "贴地不该有 x");
            JsonObject ceiling = variant(variants, "up", facing, false);
            assertEquals(180, ceiling.get("x").getAsInt(), "吊顶一律 x=180");
            assertEquals(CEILING_Y.get(facing).intValue(),
                    ceiling.has("y") ? ceiling.get("y").getAsInt() : 0,
                    "吊顶 facing=" + facing + " 的 y 不对（x=180 之后平面内手性反了，要补半圈）");
            assertEquals(0, turnOf(ceiling), "贴地/吊顶靠 y 转身，不烘模型");
        }
    }

    @Test
    @DisplayName("the base sits on the mount and the lever points at the facing -- for all in-plane states")
    void theBaseIsOnTheWallAndTheLeverOnTheFacing() throws IOException {
        JsonObject variants = variants();
        int checked = 0;
        for (String mount : SIX) {
            for (String facing : SIX) {
                for (boolean powered : new boolean[] { false, true }) {
                    JsonObject variant = variant(variants, mount, facing, powered);
                    String model = variant.get("model").getAsString();
                    assertTrue(model.startsWith("redstonecircuit:block/trigger"), mount + "/" + facing + " 的模型不对：" + model);
                    assertEquals(powered, model.startsWith("redstonecircuit:block/trigger_on"),
                            mount + "/" + facing + " 点亮点错了模型：" + model);
                    int[][] m = transform(variant);
                    // 参考模型：背面（local -Y）贴地，杠杆朝北（local -Z）
                    assertEquals(mount, name(apply(m, new int[] { 0, -1, 0 })),
                            mount + "/" + facing + "：底没有贴在挂载那一面");
                    if (!alongMountAxis(mount, facing)) {
                        assertEquals(facing, name(apply(m, new int[] { 0, 0, -1 })),
                                mount + "/" + facing + "：杠杆没有指向它读的那一面");
                        checked++;
                    }
                }
            }
        }
        assertEquals(48, checked, "6 个挂载 × 4 个平面内朝向 × 2 个视觉状态 = 48");
    }

    @Test
    @DisplayName("one wall draws four different poses, and always uses the same mount pose")
    void oneWallFourPoses() throws IOException {
        JsonObject variants = variants();
        for (String mount : List.of("north", "south", "east", "west")) {
            Set<String> models = new LinkedHashSet<>();
            List<Integer> ys = new ArrayList<>();
            for (String facing : SIX) {
                if (alongMountAxis(mount, facing)) {
                    continue;
                }
                JsonObject variant = variant(variants, mount, facing, false);
                models.add(variant.get("model").getAsString());
                assertEquals(90, variant.get("x").getAsInt(), mount + " 那一面墙的挂载 x 该是 90");
                ys.add(variant.has("y") ? variant.get("y").getAsInt() : 0);
            }
            assertEquals(4, models.size(), mount + " 那一面墙上四个朝向必须画出四种不同姿势，实际 " + models);
            assertEquals(1, new LinkedHashSet<>(ys).size(), mount + " 那一面墙上四个朝向的挂载 y 必须一致，实际 " + ys);
        }
    }

    @Test
    @DisplayName("the wall pose turns the model onto its own face: y = 0 贴南墙, 90 贴西墙, 180 贴北墙, 270 贴东墙")
    void theWallPoseTable() throws IOException {
        JsonObject variants = variants();
        java.util.Map<String, Integer> expected = java.util.Map.of("south", 0, "west", 90, "north", 180, "east", 270);
        for (var entry : expected.entrySet()) {
            JsonObject variant = variant(variants, entry.getKey(), "down", false);
            int y = variant.has("y") ? variant.get("y").getAsInt() : 0;
            assertEquals(entry.getValue().intValue(), y, entry.getKey() + " 的挂载 y 不对");
        }
    }

    @Test
    @DisplayName("the three baked turns exist for both visual states, and they are four different poses")
    void theBakedTurnsExist() throws IOException {
        List<String> references = new ArrayList<>();
        for (String base : List.of("trigger", "trigger_on")) {
            Path ground = Path.of("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                    base + ".json");
            String groundText = Files.readString(ground, StandardCharsets.UTF_8);
            references.add(groundText);
            Set<String> seen = new LinkedHashSet<>();
            seen.add(groundText);
            for (int turn = 1; turn <= 3; turn++) {
                Path file = Path.of("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                        base + "_turn" + turn + ".json");
                assertTrue(Files.exists(file), "缺模型 " + file.getFileName());
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonObject model = JsonParser.parseString(text).getAsJsonObject();
                assertEquals(3, model.getAsJsonArray("elements").size(), base + "_turn" + turn + " 该有完整三块几何");
                assertTrue(seen.add(text), base + "_turn" + turn + " 与另一张同姿势的模型逐字相同（转身没画出来）");
            }
        }
        // 平面内四个姿势两两不同：正反两态各四张，一共八张，不能重复
        Set<String> all = new LinkedHashSet<>();
        for (String text : references) {
            all.add(text);
        }
        assertEquals(2, all.size(), "开/关两张基础模型不该一样");
        assertNotEquals(references.get(0), references.get(1));
    }
}

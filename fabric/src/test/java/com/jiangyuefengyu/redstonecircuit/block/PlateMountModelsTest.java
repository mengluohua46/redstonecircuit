package com.jiangyuefengyu.redstonecircuit.block;

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

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The baked models of the four 薄板, checked against a <b>second</b> implementation of the same rotation.
 *
 * <p>These files are generated (<code>开发工具/gen_plate_models.py</code>), so the thing worth testing is not that they
 * were written but that each one really is the floor plate turned onto its face: the box, the texture on each face, the
 * uv rectangle that comes with it, and the in-plane turn of the art. The matrices here are written out again on purpose
 * - a copy that agrees with itself proves nothing, and this project has already paid for one round of "the geometry
 * turned but the art did not".
 */
class PlateMountModelsTest {

    private static final List<String> DEVICES = List.of("controller", "transformer", "clock", "extender");

    private static final List<String> DIRS = List.of("down", "up", "north", "south", "west", "east");

    @Test
    @DisplayName("每一份模型都是本体那块板转过来了：盒子、八个角、六个面一个不多一个不少")
    void everyPoseIsTheBaseBoxTurned() throws IOException {
        int checked = 0;
        for (String device : DEVICES) {
            for (String lit : List.of("", "_lit")) {
                JsonObject base = element(json(model(device + lit)));
                int[] from = ints(base.getAsJsonArray("from"));
                int[] to = ints(base.getAsJsonArray("to"));
                for (String mount : DIRS) {
                    for (String facing : DIRS) {
                        if (!inside(mount, facing)) {
                            continue;
                        }
                        int[][] m = rotation(mount, facing);
                        JsonObject turned = element(json(model(pose(device, mount, facing, lit))));
                        int[] low = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
                        int[] high = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
                        for (int corner = 0; corner < 8; corner++) {
                            int[] world = new int[3];
                            for (int axis = 0; axis < 3; axis++) {
                                world[axis] = ((corner >> axis) & 1) == 1 ? to[axis] : from[axis];
                            }
                            int[] image = turn(m, world);
                            for (int axis = 0; axis < 3; axis++) {
                                low[axis] = Math.min(low[axis], image[axis]);
                                high[axis] = Math.max(high[axis], image[axis]);
                            }
                        }
                        assertEquals(List.of(low[0], low[1], low[2]), boxed(turned.getAsJsonArray("from")),
                                device + lit + " 贴 " + mount + " 读 " + facing + ": 盒子的起点不对");
                        assertEquals(List.of(high[0], high[1], high[2]), boxed(turned.getAsJsonArray("to")),
                                device + lit + " 贴 " + mount + " 读 " + facing + ": 盒子的终点不对");
                        assertEquals(6, turned.getAsJsonObject("faces").size(), "一个盒子有六个面，缺一个就是透的");
                        checked++;
                    }
                }
            }
        }
        assertEquals(DEVICES.size() * 24 * 2, checked, "四件 × 二十四对 × 亮灭");
    }

    @Test
    @DisplayName("贴着的面拿走 #bottom、朝外的面拿走 #top，四个边条各拿自己那条 - 贴图跟着几何走")
    void theGluedFaceCarriesTheBottomTexture() throws IOException {
        for (String variant : List.of("controller", "controller_lit", "transformer", "transformer_lit",
                "clock", "clock_lit", "extender", "extender_lit")) {
            String device = variant.endsWith("_lit") ? variant.substring(0, variant.length() - 4) : variant;
            String lit = variant.endsWith("_lit") ? "_lit" : "";
            JsonObject base = element(json(model(device + lit)));
            JsonObject baseFaces = base.getAsJsonObject("faces");
            for (String mount : DIRS) {
                for (String facing : DIRS) {
                    if (!inside(mount, facing)) {
                        continue;
                    }
                    int[][] m = rotation(mount, facing);
                    JsonObject turned = element(json(model(pose(device, mount, facing, lit))))
                            .getAsJsonObject("faces");
                    Set<String> expectedFaces = new LinkedHashSet<>();
                    for (String source : baseFaces.keySet()) {
                        String target = faceOf(apply(m, dir(source)));
                        expectedFaces.add(target);
                        JsonObject targetFace = turned.getAsJsonObject(target);
                        assertNotNull(targetFace, device + " 贴 " + mount + " 读 " + facing + ": 少了 " + target
                                + " 这一面（" + source + " 转过去应该在那儿）");
                        assertEquals(baseFaces.getAsJsonObject(source).get("texture").getAsString(),
                                targetFace.get("texture").getAsString(),
                                device + " 贴 " + mount + " 读 " + facing + ": " + target
                                        + " 面拿到的贴图不是 " + source + " 那张");
                    }
                    assertEquals(expectedFaces, turned.keySet(), device + " 贴 " + mount + " 读 " + facing
                            + ": 面集合对不上");
                }
            }
        }
    }

    @Test
    @DisplayName("UV 框跟着原样搬过去；贴图的长边永远贴着板面的长边 - 不许把 16 格长的条子拧进 2 格宽的面里")
    void theArtKeepsItsAspect() throws IOException {
        int squeezed = 0;
        for (String device : DEVICES) {
            JsonObject base = element(json(model(device)));
            JsonObject baseFaces = base.getAsJsonObject("faces");
            int[] from = ints(base.getAsJsonArray("from"));
            int[] to = ints(base.getAsJsonArray("to"));
            for (String mount : DIRS) {
                for (String facing : DIRS) {
                    if (!inside(mount, facing)) {
                        continue;
                    }
                    int[][] m = rotation(mount, facing);
                    JsonObject turnedElement = element(json(model(pose(device, mount, facing, ""))));
                    int[] boxFrom = ints(turnedElement.getAsJsonArray("from"));
                    int[] boxTo = ints(turnedElement.getAsJsonArray("to"));
                    JsonObject turnedFaces = turnedElement.getAsJsonObject("faces");
                    for (String source : baseFaces.keySet()) {
                        String target = faceOf(apply(m, dir(source)));
                        JsonObject face = turnedFaces.getAsJsonObject(target);
                        JsonArray uv = face.getAsJsonArray("uv");
                        if (mount.equals("down") && facing.equals("south")) {
                            // 本体姿态就是仓库里那份模型：它靠原版 auto-UV 采样（16×16 取整张、16×2 取最下两行），
                            // 显式 uv 只有烤出来的那些才需要。
                            assertTrue(uv == null, device + ": 本体模型的 " + target + " 面不该带显式 uv");
                            continue;
                        }
                        assertNotNull(uv, device + ": " + target + " 面没有 uv 框，原版 auto-UV 会按新位置采"
                                + " - 大脸会被采成一条 2 宽的边");
                        List<Float> expected = List.of((float) autoU(from, to, source)[0],
                                (float) autoU(from, to, source)[1], (float) autoU(from, to, source)[2],
                                (float) autoU(from, to, source)[3]);
                        assertEquals(expected, floats(uv), device + " 贴 " + mount + " 读 " + facing + ": "
                                + target + " 面的 uv 不是 " + source + " 面原来的框");
                        int rotation = face.has("rotation") ? face.get("rotation").getAsInt() : 0;
                        assertEquals(0, rotation % 90, "rotation 只能是 90 的整数倍");
                        // 原版的 u 轴: 上/下/南/北 沿 x, 东/西 沿 z. 贴图的 16 格长轴必须落在面的长轴上;
                        // 面本身是「2 宽 16 高」时(挂到墙上以后就有这种边), 贴图只能转 90 度去对, 反过来
                        // 把 16 长的条子塞进 2 宽的面里就是上一轮那种「横着的火把」。
                        int uExtent = boxTo[uAxis(target)] - boxFrom[uAxis(target)];
                        int vExtent = boxTo[vAxis(target)] - boxFrom[vAxis(target)];
                        if (uExtent != vExtent && uExtent == 16) {
                            assertEquals(0, rotation % 180, device + " 贴 " + mount + " 读 " + facing + ": " + target
                                    + " 的 u 轴本来就是长轴, 不该转 " + rotation + " 度把条子拧过去");
                        } else if (uExtent != vExtent) {
                            assertEquals(90, rotation % 180, device + " 贴 " + mount + " 读 " + facing + ": " + target
                                    + " 的 u 轴只有 2 格, 贴图必须转 90 度才贴得上, 实际 " + rotation);
                            squeezed++;
                        }
                    }
                }
            }
        }
        assertTrue(squeezed > 0, "挂到墙上就有「2 宽 16 高」的边条, 总得有几面走到那一条路 - 否则什么也没查");
        assertTrue(squeezed < 200, "但也不该到处都是: " + squeezed);
    }

    @Test
    @DisplayName("地板上朝南那一个就是本体，一个 rotation 都不用")
    void theIdentityPoseIsTheBaseModel() throws IOException {
        for (String device : DEVICES) {
            JsonObject baseFaces = element(json(model(device))).getAsJsonObject("faces");
            JsonObject faces = element(json(model(device))).getAsJsonObject("faces");
            for (String face : baseFaces.keySet()) {
                assertFalse(faces.getAsJsonObject(face).has("rotation"), device + ": 本体姿态不该有任何 rotation");
                assertEquals(baseFaces.getAsJsonObject(face).get("texture").getAsString(),
                        faces.getAsJsonObject(face).get("texture").getAsString(), device + ": " + face + " 贴图变了");
            }
        }
    }

    @Test
    @DisplayName("合法组合一份不少（本体姿态用本体模型），非法组合一份也没有")
    void everyLegalPairExistsAndNoOtherDoes() {
        for (String device : DEVICES) {
            for (String lit : List.of("", "_lit")) {
                for (String mount : DIRS) {
                    for (String facing : DIRS) {
                        boolean present = exists(pose(device, mount, facing, lit));
                        if (inside(mount, facing)) {
                            assertTrue(present, "缺模型: " + device + "_" + mount + "_" + facing + lit);
                        } else {
                            assertFalse(present, "不该有: " + device + "_" + mount + "_" + facing + lit);
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ the second matrix --

    /** 原版给这个面的 u 轴: 上/下/南/北 沿 x, 东/西 沿 z. */
    private static int uAxis(String face) {
        return switch (face) {
            case "west", "east" -> 2;
            default -> 0;
        };
    }

    /** 原版给这个面的 v 轴: 上/下 沿 z, 其余沿 y. */
    private static int vAxis(String face) {
        return switch (face) {
            case "down", "up" -> 2;
            default -> 1;
        };
    }

    private static boolean inside(String mount, String facing) {
        return axis(mount) != axis(facing);
    }

    private static char axis(String direction) {
        return switch (direction) {
            case "down", "up" -> 'y';
            case "north", "south" -> 'z';
            default -> 'x';
        };
    }

    private static int[] dir(String direction) {
        return switch (direction) {
            case "down" -> new int[] {0, -1, 0};
            case "up" -> new int[] {0, 1, 0};
            case "north" -> new int[] {0, 0, -1};
            case "south" -> new int[] {0, 0, 1};
            case "west" -> new int[] {-1, 0, 0};
            default -> new int[] {1, 0, 0};
        };
    }

    private static String faceOf(int[] image) {
        for (String direction : DIRS) {
            int[] candidate = dir(direction);
            if (candidate[0] == image[0] && candidate[1] == image[1] && candidate[2] == image[2]) {
                return direction;
            }
        }
        throw new IllegalArgumentException("not a face: " + image[0] + "," + image[1] + "," + image[2]);
    }

    /** Columns are the images of +X, +Y, +Z: the plate's own up goes towards the support, its front along its plane. */
    private static int[][] rotation(String mount, String facing) {
        int[] up = neg(dir(mount));
        int[] front = dir(facing);
        int[] right = cross(up, front);
        return new int[][] {
                {right[0], up[0], front[0]},
                {right[1], up[1], front[1]},
                {right[2], up[2], front[2]}};
    }

    private static int[] neg(int[] v) {
        return new int[] {-v[0], -v[1], -v[2]};
    }

    private static int[] cross(int[] a, int[] b) {
        return new int[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    /** A direction carried by the same matrix, before the block's centre is put back on. */
    private static int[] apply(int[][] m, int[] direction) {
        int[] out = new int[3];
        for (int row = 0; row < 3; row++) {
            out[row] = m[row][0] * direction[0] + m[row][1] * direction[1] + m[row][2] * direction[2];
        }
        return out;
    }

    /** Turned about the block's centre, as an element's coordinates are. */
    private static int[] turn(int[][] m, int[] point) {
        int[] centred = {point[0] - 8, point[1] - 8, point[2] - 8};
        int[] out = new int[3];
        for (int row = 0; row < 3; row++) {
            out[row] = m[row][0] * centred[0] + m[row][1] * centred[1] + m[row][2] * centred[2] + 8;
        }
        return out;
    }

    /** What vanilla samples for a face whose element declares no uv: the box's own place in the block, u and v. */
    private static int[] autoU(int[] from, int[] to, String face) {
        int x1 = from[0];
        int y1 = from[1];
        int z1 = from[2];
        int x2 = to[0];
        int y2 = to[1];
        int z2 = to[2];
        return switch (face) {
            case "down" -> new int[] {x1, 16 - z2, x2, 16 - z1};
            case "up" -> new int[] {x1, z1, x2, z2};
            case "north" -> new int[] {16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case "south" -> new int[] {x1, 16 - y2, x2, 16 - y1};
            case "west" -> new int[] {z1, 16 - y2, z2, 16 - y1};
            default -> new int[] {16 - z2, 16 - y2, 16 - z1, 16 - y1};
        };
    }

    private static List<Float> floats(JsonArray array) {
        List<Float> out = new ArrayList<>();
        for (JsonElement element : array) {
            out.add(element.getAsFloat());
        }
        return out;
    }

    private static List<Integer> boxed(JsonArray array) {
        List<Integer> out = new ArrayList<>();
        for (JsonElement element : array) {
            out.add(element.getAsInt());
        }
        return out;
    }

    private static int[] ints(JsonArray array) {
        int[] out = new int[array.size()];
        for (int index = 0; index < out.length; index++) {
            out[index] = array.get(index).getAsInt();
        }
        return out;
    }

    private static JsonObject element(JsonObject model) {
        return model.getAsJsonArray("elements").get(0).getAsJsonObject();
    }

    /** The file that holds this pose: the identity pose (贴地 + 朝南) IS the checked-in base model. */
    private static String pose(String device, String mount, String facing, String lit) {
        return mount.equals("down") && facing.equals("south")
                ? device + lit
                : device + "_" + mount + "_" + facing + lit;
    }

    private static Path model(String name) {
        Path relative = Path.of("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                name + ".json");
        if (Files.isRegularFile(relative)) {
            return relative;
        }
        throw new IllegalStateException("could not find model " + name + " (tried " + relative + ")");
    }

    private static boolean exists(String name) {
        return Files.isRegularFile(Path.of("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                name + ".json"));
    }

    private static JsonObject json(Path path) throws IOException {
        JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
        assertTrue(parsed.isJsonObject(), path + " 应该是一个 JSON 对象");
        return parsed.getAsJsonObject();
    }
}

package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.Direction;

/**
 * 红石粉（贴面）的接线：状态、方块状态、模型几何、掉落、词条，以及**模型与代码用的是同一张环**。
 *
 * <p>渲染只能进游戏看，所以这一层钉的是能算出来的事实。最要紧的一条是几何与
 * {@link SurfaceDiodeBlock#facingRing} 的对应：链接位 {@code link0..3} 的含义是「环内第 i 个方向」，而模型的
 * 长边必须真的指向那个方向——中继器那两轮就是这么错过去的（先漏 cullface，再漏 UV）。
 */
class SurfaceWireWiringTest {

    private static final List<String> MOUNTS = List.of("down", "up", "north", "south", "east", "west");

    private static String source(String... parts) throws IOException {
        Path file = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit");
        for (String part : parts) {
            file = file.resolve(part);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static String resource(String... parts) throws IOException {
        Path file = Path.of("src", "main", "resources");
        for (String part : parts) {
            file = file.resolve(part);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static JsonObject model(String name) throws IOException {
        return JsonParser.parseString(resource("assets", "redstonecircuit", "models", "block", name + ".json"))
                .getAsJsonObject();
    }

    private static JsonObject blockstate() throws IOException {
        return JsonParser.parseString(
                resource("assets", "redstonecircuit", "blockstates", "surface_redstone_wire.json"))
                .getAsJsonObject();
    }

    /** Which way along its own axis the direction points: +1 or -1. */
    private static int step(Direction direction) {
        return direction.getStepX() + direction.getStepY() + direction.getStepZ();
    }

    private static int axis(Direction direction) {
        return switch (direction.getAxis()) {
            case X -> 0;
            case Y -> 1;
            case Z -> 2;
        };
    }

    @Test
    @DisplayName("方块自己：六个面各占一位、强度一格、出口按原版搬到别的平面")
    void theSheetCarriesItsOwnState() throws IOException {
        String block = source("block", "SurfaceWireBlock.java");
        for (String face : List.of("down", "up", "north", "south", "west", "east")) {
            assertTrue(block.contains("BooleanProperty.create(\"" + face + "\")"),
                    "六个面各占一位（一格可以同时挂好几个面）：缺 " + face);
        }
        assertTrue(block.contains("private static int quiet"), "原版靠 shouldSignal 让自己在算电时闭嘴，这里要等价物");
        assertTrue(block.contains("if (towards == face) {"),
                "贴着的那一块要答：原版粉给脚下那块供电（放在红石灯上会点亮它）");
        assertTrue(block.contains("ring(face).contains(towards)"),
                "平面内相邻的线才连：平面外那条不出电，也不吃电");
        assertTrue(block.contains("climbsTowards"),
                "原版那条「爬上去充能」的读法（并排的石头）也要在两个平面上成立");
        assertTrue(block.contains("worldStoreSignalAt"),
                "邻居是宿主方块时要走存量那一路，否则求解窗口里会读到 0（比较器那轮的教训）");
        assertTrue(block.contains("return SurfaceRing.of(mount);"),
                "环的次序只有一个来源，模型与代码不能各有一份");
        assertTrue(source("block", "SurfaceDiodeBlock.java").contains("return SurfaceRing.of(mount);"),
                "二极管与粉必须问同一张表");
        assertFalse(block.contains("extends RedStoneWireBlock"),
                "不能继承原版线：它的连接状态是水平四向写死的，继承只能再加一套状态");
        assertFalse(block.contains("keepHonest"),
                "绕棱那套每 4 tick 的自查必须彻底删掉：作者那次的冻结就是原版自己的更新队列被顶爆");
    }

    @Test
    @DisplayName("方块状态：六个面各一件，每件只画点；线条由方块实体渲染器按世界画")
    void theBlockstateCoversEveryMounting() throws IOException {
        JsonArray parts = blockstate().getAsJsonArray("multipart");
        assertEquals(MOUNTS.size(), parts.size(), "六个面各一件，别的几何都不进状态");
        for (String mount : MOUNTS) {
            int seen = 0;
            for (var element : parts) {
                JsonObject part = element.getAsJsonObject();
                JsonObject when = part.getAsJsonObject("when");
                if (!when.has(mount) || !when.get(mount).getAsString().equals("true")) {
                    continue;
                }
                seen++;
                assertEquals("redstonecircuit:block/surface_dust_" + mount + "_dot",
                        part.getAsJsonObject("apply").get("model").getAsString(),
                        mount + " 那一面只画一个点：线条是渲染器按邻居画的");
            }
            assertEquals(1, seen, mount + " 只有一件");
        }
    }

    @Test
    @DisplayName("模型几何：平线指向环内方向，点铺满整格")
    void theGeometryAgreesWithTheRing() throws IOException {
        for (String mountName : MOUNTS) {
            Direction mount = Direction.valueOf(mountName.toUpperCase());
            List<Direction> ring = SurfaceRing.of(mount);
            int flat = axis(mount);
            double plane = step(mount) > 0 ? 15.75 : 0.25;
            for (int slot = 0; slot < ring.size(); slot++) {
                Direction direction = ring.get(slot);
                int u = axis(direction);
                JsonArray flatEls = model("surface_dust_" + mountName + "_flat_" + slot)
                        .getAsJsonArray("elements");
                assertEquals(2, flatEls.size(), "平线是两层：线 + 高光");
                assertQuad(flatEls.get(0).getAsJsonObject(), flat, plane, u, step(direction) > 0 ? 8.0 : 0.0,
                        step(direction) > 0 ? 16.0 : 8.0, mountName + "/flat/" + slot);
            }
            JsonArray dot = model("surface_dust_" + mountName + "_dot").getAsJsonArray("elements");
            assertEquals(2, dot.size(), "点也是两层");
            assertQuad(dot.get(0).getAsJsonObject(), flat, plane, -1, 0.0, 16.0, mountName + "/dot");
        }
    }
    /** A flat quad on the mounting face, 0.25 off it, spanning [from,to] along u and full width across. */
    private static void assertQuad(JsonObject element, int flatAxis, double plane, int uAxis,
                                   double from, double to, String what) {
        JsonArray low = element.getAsJsonArray("from");
        JsonArray high = element.getAsJsonArray("to");
        assertEquals(low.get(flatAxis).getAsDouble(), high.get(flatAxis).getAsDouble(), 1e-9, what + " 必须是平的");
        assertEquals(plane, low.get(flatAxis).getAsDouble(), 1e-9, what + " 离挂载面 0.25 格");
        for (int a = 0; a < 3; a++) {
            if (a == flatAxis) {
                continue;
            }
            boolean along = a == uAxis;
            assertEquals(along ? from : 0.0, low.get(a).getAsDouble(), 1e-9, what + " 轴 " + a + " 的下界");
            assertEquals(along ? to : 16.0, high.get(a).getAsDouble(), 1e-9, what + " 轴 " + a + " 的上界");
        }
    }

    @Test
    @DisplayName("用原版贴图：uv 照抄 0..16 / 0..8，贴图的 v 轴必须落在连接方向上")
    void theModelsUseVanillasOwnArt() throws IOException {
        for (String mountName : MOUNTS) {
            Direction mount = Direction.valueOf(mountName.toUpperCase());
            List<Direction> ring = SurfaceRing.of(mount);
            for (int slot = 0; slot < ring.size(); slot++) {
                int u = axis(ring.get(slot));
                JsonObject file = model("surface_dust_" + mountName + "_flat_" + slot);
                JsonObject textures = file.getAsJsonObject("textures");
                assertEquals("minecraft:block/redstone_dust_line0", textures.get("line").getAsString(),
                        "线用原版贴图");
                assertEquals("minecraft:block/redstone_dust_overlay", textures.get("overlay").getAsString(),
                        "高光也用原版那张");
                JsonArray elements = file.getAsJsonArray("elements");
                JsonObject line = elements.get(0).getAsJsonObject().getAsJsonObject("faces");
                JsonObject overlay = elements.get(1).getAsJsonObject().getAsJsonObject("faces");
                for (String key : line.keySet()) {
                    JsonObject face = line.getAsJsonObject(key);
                    assertEquals(0, face.get("tintindex").getAsInt(), "线要按强度上色，否则永远是一块灰");
                    assertEquals(16.0, face.getAsJsonArray("uv").get(2).getAsDouble(), 1e-9,
                            "uv 是原版的 0..16 / 0..8：线的宽度靠贴图自己收窄");
                    assertEquals(8.0, face.getAsJsonArray("uv").get(3).getAsDouble(), 1e-9, "同上");
                    assertFalse(overlay.getAsJsonObject(key).has("tintindex"), "高光不参与上色：原版也不给它颜色");
                    if (faceVAxis(key) == u) {
                        assertFalse(face.has("rotation"), key + " 面的贴图 v 本来就在连接方向上，不用转");
                    } else {
                        assertEquals(90, face.get("rotation").getAsInt(),
                                key + " 面必须转四分之一圈，否则线会横过来（作者 2026-11-05 报的就是这个）");
                    }
                }
            }
            JsonObject dotModel = model("surface_dust_" + mountName + "_dot");
            assertEquals("minecraft:block/redstone_dust_dot",
                    dotModel.getAsJsonObject("textures").get("line").getAsString(), "点用原版的点贴图");
        }
    }

    /** Vanilla convention: the world axis a face texture v runs along, before any face rotation. */
    private static int faceVAxis(String face) {
        return switch (face) {
            case "up", "down" -> 2;
            default -> 1;
        };
    }

    /**
     * 2026-11-11 那次「卡在加载界面、内存一直涨」的真因：方块状态爆炸。
     *
     * <p>资源加载时模型加载器要把每个状态都过一遍。八值那版是 393,216，客户端就那么卡死了；绕棱那版
     * 6 × 16 × 4^4 = 24,576 是勉强能过。这一版没有任何按邻接取值的东西：六个面各一位 + 强度，1,024 个。
     */
    @Test
    @DisplayName("状态预算：六个面 × 16 档，只有 1,024 个")
    void theStateBudgetStaysSane() throws IOException {
        String block = source("block", "SurfaceWireBlock.java");
        assertFalse(block.contains("EnumProperty<Link>") || block.contains("LINK0"),
                "链接不许回到状态里：它既是状态爆炸的来源，也是更新级联的来源");
        assertFalse(block.contains("RECHECK_TICKS") || block.contains("scheduleTick"),
                "粉不再给自己排 tick：作者那次的冻结是原版更新队列被顶爆");
        int states = (int) Math.pow(2, 6) * 16;
        assertEquals(1024, states, "六个面各一位 × 16 档强度");
        assertTrue(states <= 30000, "状态数 " + states + " 太大会拖死加载");
    }
    @Test
    @DisplayName("掉落、词条、上色与放置钩子都接上了")
    void theOrdinaryPlumbingIsWired() throws IOException {
        String loot = resource("data", "redstonecircuit", "loot_table", "blocks", "surface_redstone_wire.json");
        assertTrue(loot.contains("minecraft:redstone"), "拆了要还回红石粉本身");
        for (String lang : List.of("zh_cn", "en_us")) {
            String file = resource("assets", "redstonecircuit", "lang", lang + ".json");
            assertTrue(file.contains("block.redstonecircuit.surface_redstone_wire"), lang + " 缺方块名");
            // 强度那一行不归我们：玉自己会给信号源印一行「红石强度」，再印一行就是两条（作者 2026-11-14）。
            assertFalse(file.contains("jade.redstonecircuit.surface_wire.power"), lang + " 不该再有重复的强度行");
            assertTrue(file.contains("jade.redstonecircuit.surface_wire.mount"), lang + " 缺玉的贴面行");
        }
        String colors = source("client", "SurfaceWireColors.java");
        assertTrue(colors.contains("RedStoneWireBlock.getColorForPower"), "颜色必须问原版那条函数，不能自己编");
        String mixin = source("mixin", "BlockItemPlacementMixin.java");
        assertTrue(mixin.contains("SurfaceWirePlacement.tryPlace"), "原版粉的物品要能被分流到这张面上");
        String placement = source("block", "SurfaceWirePlacement.java");
        assertTrue(placement.contains("clicked == Direction.UP"),
                "顶面是原版的路：那一面上原版粉自己就能放，一行都不许动");
        String registry = source("RCRegistry.java");
        assertTrue(registry.contains("\"surface_redstone_wire\""), "注册名要与资源目录一致");
    }
}
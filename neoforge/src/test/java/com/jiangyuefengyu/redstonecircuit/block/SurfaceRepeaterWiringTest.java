package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 中继器（贴面）的接线：六个挂载面、方块状态、掉落、以及「只在原版放不上去的面上接手」这条放置规矩。
 *
 * <p>渲染只能进游戏看，所以这一层钉的是**能读出来的事实**：状态里有 {@code mount}、六种挂载各有一套形状、
 * blockstate 覆盖全部组合、墙面那两种朝向的模型是**按面转过几何**的（不是只转了方块状态）、掉落回原版中继器、
 * 钩子只碰原版答不了的那些点击。
 */
class SurfaceRepeaterWiringTest {

    private static final List<String> MOUNTS = List.of("down", "up", "north", "south", "east", "west");
    private static final List<String> WALLS = List.of("north", "south", "east", "west");

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

    private static JsonObject models() throws IOException {
        return JsonParser.parseString(
                resource("assets", "redstonecircuit", "blockstates", "surface_repeater.json"))
                .getAsJsonObject().getAsJsonObject("variants");
    }

    @Test
    @DisplayName("方块自己：挂载面是独立属性、六张形状表、掉了挂载物就掉自己")
    void theBlockHangsOnSixFaces() throws IOException {
        String block = source("block", "SurfaceDiodeBlock.java");
        assertTrue(block.contains("DirectionProperty.create(\"mount\", Direction.values())"),
                "挂载面要和 facing 分开：一个是贴在哪一面，一个是二极管读哪一面");
        for (String mount : List.of("Direction.DOWN", "Direction.UP", "Direction.NORTH", "Direction.SOUTH",
                "Direction.EAST", "Direction.WEST")) {
            assertTrue(block.contains(mount + ", Block.box("), mount + " 缺一套形状：板子要贴在那一面上");
        }
        assertTrue(block.contains("CarriedSupport.sturdy(")
                        && block.contains("pos.relative(mount), mount.getOpposite(), SupportType.RIGID"),
                "能不能挂住，问的是挂载面那一格朝我这一面结不结实——而且要走两帧那一条读，"
                        + "被搬走的方块在世界那一格是空气（作者 2026-11-16）");
        assertFalse(block.contains("super.updateShape"),
                "不能转给 RepeaterBlock 的 updateShape：那一条把「下面不结实就掉」写死了，贴墙的中继器会被地板换掉时误伤");
        assertTrue(block.contains("setValue(MOUNT, rotation.rotate(state.getValue(MOUNT)))")
                        && block.contains("setValue(MOUNT, mirror.mirror(state.getValue(MOUNT)))"),
                "结构旋转/镜像要带着挂载面一起转");
    }

    @Test
    @DisplayName("墙面上的朝向只能是墙内的那两个：瞄准墙里放会掰到最近的一个")
    void aWallMountOnlyHonoursInPlaneFacings() throws IOException {
        String block = source("block", "SurfaceRepeaterBlock.java");
        String base = source("block", "SurfaceDiodeBlock.java");
        assertTrue(base.contains("public static boolean facingFitsMount(Direction mount, Direction facing)")
                        && base.contains("return facing.getAxis() != mount.getAxis();"),
                "朝向必须落在板面里：贴墙时上下也算，贴地/吊顶时只能是四个水平方向");
        assertTrue(base.contains("return mount.getAxis().isVertical() ? Direction.NORTH : mount.getClockWise();"),
                "瞄准墙里放的时候掰到墙内那个朝向，而不是留下一个画不出来的组合");
        assertTrue(base.contains("public static Direction placementFacing(Direction mount, Direction whereThePlayerLooks,"),
                "放置要按玩家朝哪看决定：低头看墙就放一块读上面的板子回来");
        // 环的那张表已经搬到 SurfaceRing：二极管与红石粉（贴面）共用一张，两处都只能问它。
        String ring = source("block", "SurfaceRing.java");
        assertTrue(ring.contains("List.of(Direction.UP, mount.getClockWise(), Direction.DOWN, "
                        + "mount.getCounterClockWise())"),
                "墙上的四态环是「上、右、下、左」：从板子外面看过去的顺时针，上下也在里面");
        assertTrue(ring.contains("List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)"),
                "贴地/吊顶的四态环还是水平那四个");
        assertTrue(base.contains("return SurfaceRing.of(mount);"), "二极管要问那张唯一的表");
        String placement = source("block", "SurfaceRepeaterPlacement.java");
        assertTrue(placement.contains("SurfaceDiodeBlock.placementFacing(mount,"),
                "放置路径也要走同一条判定：不然电气朝向和画出来的朝向会对不上");
    }

    @Test
    @DisplayName("blockstate 覆盖 6 × 4 × 4 × 2 × 2；贴地/吊顶用原版模型，墙面用按面转过几何的那份")
    void theBlockstateCoversEveryMounting() throws IOException {
        JsonObject variants = models();
        // 六个挂载面 × 六个朝向 × 4 档延迟 × 锁定 × 通电 = 576。每个挂载面只有四个朝向是画得出来的（板面内），
        // 另外两个留着兜底，免得 /setblock 出来的状态变成紫黑方块。
        assertEquals(576, variants.size(), "变体总数不对");

        Set<String> ownModels = new TreeSet<>();
        int[] perMount = new int[MOUNTS.size()];
        for (String key : variants.keySet()) {
            JsonObject variant = variants.getAsJsonObject(key);
            String model = variant.get("model").getAsString();
            String mount = value(key, "mount");
            String facing = value(key, "facing");
            perMount[MOUNTS.indexOf(mount)]++;
            int x = variant.has("x") ? variant.get("x").getAsInt() : 0;
            int y = variant.has("y") ? variant.get("y").getAsInt() : 0;

            boolean fits = !axisOf(mount).equals(axisOf(facing));
            if (mount.equals("down") || mount.equals("up")) {
                assertTrue(model.startsWith("minecraft:block/repeater_"),
                        key + "：贴地和吊顶都用原版那一份，不必自己造");
                if (!fits) {
                    // A 朝向 along the mount axis cannot be produced by any gesture; the variant only exists so a
                    // command cannot leave a missing model behind.
                    continue;
                }
                if (mount.equals("down")) {
                    assertEquals(0, x, key + "：贴地的板子躺在格子底部，不需要翻");
                    assertEquals(floorY(facing), y, key + "：贴地的 y 必须和原版中继器逐字一致");
                } else {
                    assertEquals(180, x, key + "：吊顶要整个倒过来");
                    assertEquals(floorY(opposite(facing)), y,
                            key + "：翻 180 度会把正面也翻过去，y 要多转半圈才让正面仍指向 facing");
                }
            } else {
                assertTrue(model.startsWith("redstonecircuit:block/surface_repeater_" + mount + "_"),
                        key + "：墙面上的板子要按面转过几何，不能靠方块状态的 x/y");
                if (fits) {
                    assertTrue(model.contains("_" + facing + "_"), key + "：板面内的四态要各自有自己的模型");
                }
                assertEquals(0, x, key + "：墙面模型的旋转已经烘进几何里了");
                assertEquals(0, y, key + "：同上");
                ownModels.add(model);
            }
        }
        for (int i = 0; i < MOUNTS.size(); i++) {
            assertEquals(96, perMount[i], MOUNTS.get(i) + " 这一面应当有 6 朝向 × 4 延迟 × 2 锁定 × 2 通电 个变体");
        }
        // Four walls × the four in-plane facings (two horizontals plus up and down) × 16 vanilla models.
        assertEquals(256, ownModels.size(), "墙面模型：4 面墙 × 板面内 4 个朝向 × 16 个原版模型");
    }

    @Test
    @DisplayName("墙面模型是真转过几何的：板子立起来（Y 撑满、某个水平轴只剩 2）")
    void theWallModelsAreRotatedGeometry() throws IOException {
        for (String wall : WALLS) {
            for (String facing : List.of("north", "south", "east", "west", "up", "down")) {
                boolean inPlane = wall.equals("north") || wall.equals("south")
                        ? !facing.equals("north") && !facing.equals("south")
                        : !facing.equals("east") && !facing.equals("west");
                if (!inPlane) {
                    // The two facings that point into the wall are snapped away by the block; their variants reuse
                    // the in-plane model, so there is no geometry of their own to check here.
                    continue;
                }
                String path = "surface_repeater_" + wall + "_" + facing + "_repeater_1tick";
                Path file = Path.of("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                        path + ".json");
                assertTrue(Files.isRegularFile(file), "缺模型 " + path + "：那一面会变成紫黑方块");
                JsonObject model = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                double[] extent = extent(model);
                assertEquals(16.0, extent[1], 1.0E-6, path + "：整份几何必须立在 Y 方向撑满，才是贴在墙上的样子");
                // The plate itself (the slab element) is the piece that has to hug the wall: 2 px along the wall's
                // normal and a full 16 in the other two axes. The torch on top legitimately sticks out further.
                int normal = wall.equals("north") || wall.equals("south") ? 2 : 0;
                boolean hugged = false;
                for (JsonElement element : model.getAsJsonArray("elements")) {
                    JsonObject box = element.getAsJsonObject();
                    double[] size = new double[3];
                    for (int axis = 0; axis < 3; axis++) {
                        size[axis] = box.getAsJsonArray("to").get(axis).getAsDouble()
                                - box.getAsJsonArray("from").get(axis).getAsDouble();
                    }
                    if (Math.abs(size[normal] - 2.0) < 1.0E-6
                            && Math.abs(size[(normal + 1) % 3] - 16.0) < 1.0E-6
                            && Math.abs(size[(normal + 2) % 3] - 16.0) < 1.0E-6) {
                        hugged = true;
                    }
                }
                assertTrue(hugged, path + "：必须有一块 16×16×2 的板子贴着墙（法线方向是 "
                        + (normal == 2 ? "Z" : "X") + "），实际 " + java.util.Arrays.toString(extent));
            }
        }
    }

    @Test
    @DisplayName("墙面模型的贴图跟着几何一起转：电路纹路不能和火把差 90 度")
    void theWallModelsCarryTheirTextureRound() throws IOException {
        for (String wall : WALLS) {
            for (String facing : List.of("north", "south", "east", "west")) {
                boolean inPlane = wall.equals("north") || wall.equals("south")
                        ? facing.equals("east") || facing.equals("west")
                        : facing.equals("north") || facing.equals("south");
                if (!inPlane) {
                    continue;
                }
                String name = "surface_repeater_" + wall + "_" + facing + "_repeater_1tick.json";
                JsonObject model = JsonParser.parseString(Files.readString(Path.of("src", "main", "resources",
                        "assets", "redstonecircuit", "models", "block", name), StandardCharsets.UTF_8))
                        .getAsJsonObject();
                boolean turned = false;
                for (JsonElement element : model.getAsJsonArray("elements")) {
                    JsonObject faces = element.getAsJsonObject().getAsJsonObject("faces");
                    for (String direction : faces.keySet()) {
                        JsonObject face = faces.getAsJsonObject(direction);
                        if (!face.has("texture") || !face.get("texture").getAsString().equals("#top")) {
                            continue;
                        }
                        // The wall transforms are 180 degree turns about a diagonal axis: every face's uv frame
                        // lands 90 or 270 degrees round, so the face must say so or the art lies across the
                        // geometry (the two torches horizontal while the drawn line ran up and down).
                        assertTrue(face.has("rotation") && face.get("rotation").getAsInt() % 180 != 0,
                                name + "：" + direction + " 面的电路贴图没有跟着几何转，纹路会和火把差 90 度");
                        turned = true;
                    }
                }
                assertTrue(turned, name + "：找不到 #top 那一面");
            }
        }
    }

    @Test
    @DisplayName("blockstate 里点名的每个自有模型都在盘上：少一个就是紫黑方块")
    void everyNamedModelExists() throws IOException {
        for (String key : models().keySet()) {
            String model = models().getAsJsonObject(key).get("model").getAsString();
            if (!model.startsWith("redstonecircuit:block/")) {
                continue;
            }
            String name = model.substring("redstonecircuit:block/".length()) + ".json";
            assertTrue(Files.isRegularFile(Path.of("src", "main", "resources", "assets", "redstonecircuit",
                    "models", "block", name)), key + " → " + name + " 不存在");
        }
    }

    @Test
    @DisplayName("拆掉一个回复成原版中继器：它没有自己的物品")
    void breakingOneGivesAVanillaRepeater() throws IOException {
        String loot = resource("data", "redstonecircuit", "loot_table", "blocks", "surface_repeater.json");
        assertTrue(loot.contains("\"name\": \"minecraft:repeater\""),
                "拆掉必须回到原版中继器，否则玩家会丢东西");
        String block = source("block", "SurfaceRepeaterBlock.java");
        assertTrue(block.contains("new ItemStack(Items.REPEATER)"),
                "选取方块（鼠标中键）也要给原版中继器");
    }

    @Test
    @DisplayName("放置钩子只接原版答不了的那些面：顶面那一下原样交给原版")
    void thePlacementHookOnlyTakesTheFacesVanillaCannot() throws IOException {
        String placement = source("block", "SurfaceRepeaterPlacement.java");
        assertTrue(placement.contains("item.getBlock() == Blocks.REPEATER")
                        && placement.contains("item.getBlock() == Blocks.COMPARATOR"),
                "只管原版中继器和比较器这两件物品，别的方块放置一律不碰");
        assertTrue(placement.contains("if (clicked == Direction.UP) {")
                        && placement.contains("return null;"),
                "点顶面 = 原版自己的放置，必须原样放行");
        assertTrue(placement.contains("if (!block.canStay(state, level, pos)) {"),
                "挂不住的地方也不接手：原版怎么回答就怎么回答");

        String mixin = source("mixin", "BlockItemPlacementMixin.java");
        assertTrue(mixin.contains("@Mixin(BlockItem.class)")
                        && mixin.contains("method = \"useOn\"")
                        && mached(mixin, "HEAD"),
                "钩子在 useOn 的最前面，只有拿到结果才取消原版");
        assertTrue(resource("redstonecircuit.mixins.json").contains("\"BlockItemPlacementMixin\""),
                "混入必须在 redstonecircuit.mixins.json 里登记，否则一行都不会跑");
    }

    @Test
    @DisplayName("比较器（贴面）：144 个变体、64 个按面模型、掉落回原版比较器、玉的两行字")
    void theComparatorTwinIsWiredUpToo() throws IOException {
        JsonObject variants = JsonParser.parseString(
                resource("assets", "redstonecircuit", "blockstates", "surface_comparator.json"))
                .getAsJsonObject().getAsJsonObject("variants");
        // 六个挂载面 × 六个朝向 × 比较/减法 × 通断
        assertEquals(144, variants.size(), "比较器的变体总数不对");
        Set<String> own = new TreeSet<>();
        for (String key : variants.keySet()) {
            String model = variants.getAsJsonObject(key).get("model").getAsString();
            assertTrue(model.contains("comparator"), key + " → " + model + "：外观必须是原版比较器自己的模型");
            if (model.startsWith("redstonecircuit:block/")) {
                own.add(model);
                String name = model.substring("redstonecircuit:block/".length()) + ".json";
                assertTrue(Files.isRegularFile(Path.of("src", "main", "resources", "assets", "redstonecircuit",
                        "models", "block", name)), key + " → " + name + " 不存在");
            }
        }
        assertEquals(64, own.size(), "墙面比较器模型：4 面墙 × 板面内 4 个朝向 × 4 个原版模型");
        String loot = resource("data", "redstonecircuit", "loot_table", "blocks", "surface_comparator.json");
        assertTrue(loot.contains("minecraft:comparator"), "拆掉必须回到原版比较器");
        for (String lang : List.of("zh_cn", "en_us")) {
            JsonObject file = JsonParser.parseString(
                    resource("assets", "redstonecircuit", "lang", lang + ".json")).getAsJsonObject();
            for (String key : List.of("block.redstonecircuit.surface_comparator",
                    "jade.redstonecircuit.surface_repeater.delay",
                    "jade.redstonecircuit.surface_comparator.mode",
                    "jade.redstonecircuit.surface_diode.facing")) {
                assertTrue(file.has(key), lang + " 少了 " + key);
            }
        }
    }

    @Test
    @DisplayName("两端语言都有这个名字")
    void bothLanguagesKnowIt() throws IOException {
        for (String lang : List.of("zh_cn", "en_us")) {
            JsonElement parsed = JsonParser.parseString(
                    resource("assets", "redstonecircuit", "lang", lang + ".json"));
            assertTrue(parsed.getAsJsonObject().has("block.redstonecircuit.surface_repeater"),
                    lang + " 少了 block.redstonecircuit.surface_repeater");
        }
    }

    private static boolean mached(String text, String token) {
        return text.contains("at = @At(\"" + token + "\")");
    }

    /** The bounding box of every element of a model, per axis. */
    private static double[] extent(JsonObject model) {
        double[] min = {16, 16, 16};
        double[] max = {0, 0, 0};
        for (JsonElement element : model.getAsJsonArray("elements")) {
            JsonObject box = element.getAsJsonObject();
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], box.getAsJsonArray("from").get(axis).getAsDouble());
                max[axis] = Math.max(max[axis], box.getAsJsonArray("to").get(axis).getAsDouble());
            }
        }
        return new double[] {max[0] - min[0], max[1] - min[1], max[2] - min[2]};
    }

    private static String axisOf(String facing) {
        return switch (facing) {
            case "north", "south" -> "Z";
            case "east", "west" -> "X";
            default -> "Y";
        };
    }

    private static int floorY(String facing) {
        return switch (facing) {
            case "north" -> 180;
            case "east" -> 270;
            case "west" -> 90;
            default -> 0;
        };
    }

    private static String opposite(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            default -> "east";
        };
    }

    /** The value of one {@code key=value} pair in a blockstate variant key. */
    private static String value(String variantKey, String property) {
        for (String pair : variantKey.split(",")) {
            String[] parts = pair.split("=");
            if (parts[0].equals(property)) {
                return parts[1];
            }
        }
        throw new IllegalArgumentException("no " + property + " in " + variantKey);
    }
}
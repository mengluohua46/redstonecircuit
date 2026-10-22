package com.jiangyuefengyu.redstonecircuit.blueprint;

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

/**
 * The placement hooks' wiring: the order of the two halves, and the fact that neither can take the game down.
 *
 * <p>None of this is visible in a green build, because none of it is reachable without Create installed - the mod's
 * own tests run without it, and this repository never compiles against it. What is asserted is therefore text: that
 * the world coordinates are budgeted at load and only looked up where a block lands, that the transform is written
 * once, and that the compensation pass exists and runs after the projectiles land rather than when the printer stops.
 */
class BlueprintPlacementWiringTest {

    private static final List<String> PLACEMENT_MIXINS = List.of("CreateSchematicDeployMixin",
            "CreateSchematicPlaceMixin", "CreateSchematicPacketMixin", "CreateSchematicCannonMixin");

    @Test
    @DisplayName("五个新钩子都进了同一份配置，而这份配置仍然是「没装 Create 就一条都不生效」")
    void theHooksAreRegisteredInTheGatedConfig() throws IOException {
        String config = read("src", "main", "resources", "redstonecircuit.create.mixins.json");
        assertTrue(config.contains("\"required\": false"), "配置文件不是必需的");
        assertTrue(config.contains("\"plugin\": \"com.jiangyuefengyu.redstonecircuit.mixin.CreateMixinPlugin\""),
                "每个钩子是否生效由插件回答");
        for (String mixin : PLACEMENT_MIXINS) {
            assertTrue(config.contains("\"" + mixin + "\""), mixin + " 必须在配置里列名");
        }
        assertTrue(config.contains("\"CreateSchematicSectionMixin\""), "读取点也要列名");
        assertFalse(config.contains("\"client\": [\n    \"CreateSchematicSectionMixin\""),
                "读取点与落地钩子都是服务端的事：客户端的 store 是空的");
    }

    @Test
    @DisplayName("四个面向 Create 的钩子：目标写成字符串，一个 Create 类型都不 import，且都允许缺失")
    void everyHookNamesCreateByStringOnly() throws IOException {
        for (String mixin : PLACEMENT_MIXINS) {
            String source = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                    mixin + ".java");
            assertFalse(source.contains("import com.simibubi"), mixin + " 不许 import Create 的类型");
            assertEquals(1, count(source, "com.simibubi"),
                    mixin + " 里 com.simibubi 只许出现在 @Mixin(targets = ...) 那一行");
            assertTrue(source.contains("require = 0"),
                    mixin + " 的注入点必须允许缺失：Create 改一个方法名只该让蓝图少带元件");
        }
    }

    @Test
    @DisplayName("读取点挑的是「原版拿到整份 tag」那一刻，不是再读一次文件")
    void theSectionIsTakenFromTheFilesOwnLoad() throws IOException {
        String section = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "CreateSchematicSectionMixin.java");
        assertTrue(section.contains("@Mixin(StructureTemplate.class)"),
                "整份 tag 只在 StructureTemplate#load 这一个地方出现过一次");
        assertTrue(section.contains("method = \"load\", at = @At(\"HEAD\")"),
                "在它被读之前拿到 tag：之后 Create 手上只剩模板，段就没了");
        assertEquals(0, count(section, "com.simibubi"),
                "这一个的目标是原版类，一个 Create 名字都不该出现");

        String deploy = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintDeploy.java");
        assertTrue(deploy.contains("private static final ThreadLocal<CompoundTag> SECTION"),
                "tag 只在同一次调用里从读到用，不跨方法留");
        assertTrue(deploy.contains("SECTION.remove()"),
                "用掉就清：留着只会让上一份蓝图在下一份里复活");
    }

    @Test
    @DisplayName("先预算后写回：旋转与镜像只算一次，落地钩子只查表")
    void theTransformHappensOnceAtLoadTime() throws IOException {
        String deploy = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintDeploy.java");
        assertTrue(deploy.indexOf("public static void afterSchematicLoad") < deploy.indexOf("public static void blockLanded"),
                "预算必须在写回之前定义：读到蓝图的那一刻算完，落地时只查表");
        assertTrue(deploy.contains("BlueprintPlacement.budget("), "预算是放置侧唯一的坐标来源");
        assertEquals(0, count(deploy, "StructureTemplate.transform"),
                "落地侧不许自己做变换：那会把浮点误差和手写符号错误带进每个方块的循环");
        assertTrue(deploy.contains("Class.forName(CREATE_SCHEMATIC_ITEM"),
                "旋转与镜像问的是物品，不是文件：文件里根本没有这两样东西");
        assertTrue(deploy.contains("\"getSettings\", ItemStack.class"), "问的就是 Create 自己放置时用的那一个");

        String placement = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintPlacement.java");
        assertEquals(1, count(placement, "StructureTemplate.transform("),
                "位置变换只有一处：原版的整数版 transform，且锚点后加、pivot 恒为 ZERO");
        assertTrue(placement.contains("mirrorOrNone(mirror).mirror(direction)")
                        && placement.contains("rotationOrNone(rotation).rotate("),
                "朝向是「先镜像后旋转」，与 placeInWorld 对 BlockState 做的顺序一致");

        String restore = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintRestore.java");
        assertEquals(0, count(restore, "Rotation"), "写回侧不认得旋转：它拿到的已经是世界坐标");
        assertEquals(0, count(restore, "Mirror"), "写回侧不认得镜像：它拿到的已经是世界坐标");
        assertEquals(0, count(restore, "toWorld"), "写回侧一次变换都不做，只查表");
    }

    @Test
    @DisplayName("落地钩子就是 Create 两条路径唯一的收口，只写 store")
    void theLandingHookOnlyWritesTheStore() throws IOException {
        String place = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "CreateSchematicPlaceMixin.java");
        assertTrue(place.contains("method = \"placeSchematicBlock\", at = @At(\"TAIL\")"),
                "方块刚落定的那一刻：物品直放、加农炮炮弹、延迟方块三条路都经过这里");
        assertTrue(place.contains("BlueprintDeploy.blockLanded(level, pos)"), "只把世界坐标交出去");

        String restore = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintRestore.java");
        assertTrue(restore.contains("setSlot("), "元件走既有的 Slot save/load 形状");
        assertTrue(restore.contains("putClock("), "时钟走 ClockRule.Setting");
        assertTrue(restore.contains("putExtender("), "延长器走 ExtenderRule.Setting");
        assertFalse(restore.contains("setBlock("), "绝不动方块：方块是 Create 的");
        assertFalse(restore.contains("getBlockEntity("), "绝不碰方块实体：元件数据不在里面");
        assertTrue(restore.contains("HostRules.isValidHost"), "目标格子必须是合法宿主，否则就是往空气里写");
        assertTrue(restore.contains("hostAccepts(level, pos)"),
                "落地那一刻也要过宿主这一关，不能只靠最后的清扫把关");
        assertTrue(restore.contains("blockAt(level, entry.getKey()) instanceof ClockBlock"),
                "时钟的设置只能落在一只时钟身上，不能落在随便什么方块底下");
        assertTrue(count(restore, "instanceof ClockBlock") >= 2 && count(restore, "instanceof ExtenderBlock") >= 2,
                "落地与清扫两条路都得问「那里站的真是这只设备吗」，否则两条路迟早会跑偏");
    }

    @Test
    @DisplayName("兜底：加农炮打印结束不算完，炮弹全部落地才算完")
    void theCompensationPassWaitsForTheProjectiles() throws IOException {
        String cannon = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "CreateSchematicCannonMixin.java");
        assertTrue(cannon.contains("method = \"finishedPrinting\", at = @At(\"HEAD\")"),
                "必须在 HEAD：Create 在返回之前就把 printer 清了，锚点也就没了");
        assertTrue(cannon.contains("method = \"tickFlyingBlocks\", at = @At(\"TAIL\")"),
                "炮弹落地是 tickFlyingBlocks 说的，那是「全部落定」的另一半");

        String deploy = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintDeploy.java");
        assertTrue(deploy.contains("BlueprintRestore.markFinal("), "还有炮弹在飞时先记下锚点");
        assertTrue(deploy.contains("BlueprintRestore.takeFinal("), "最后落定之后才做补偿写回");
        assertTrue(deploy.contains("BlueprintRestore.finish("), "两条路径都要有一次「放完了」的收尾");
        assertTrue(deploy.contains("field = cannon.getClass().getField(\"flyingBlocks\")"),
                "「还有没有炮弹」问的是那个列表");

        String restore = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintRestore.java");
        assertTrue(restore.contains("private static void sweep("), "补偿写回是一次清扫");
        assertTrue(restore.contains("WARNED.add(key)"), "丢掉的那些只 warn 一次，不是每个方块一条");
        assertTrue(restore.contains("MAX_PENDING_PER_DIMENSION"),
                "没人来取的蓝图必须有上限：被放弃的那一份不会自己消失");
    }

    @Test
    @DisplayName("两条放置路径都收口到同一个落地钩子，而不是各写一遍")
    void bothPlacementPathsEndInOneHook() throws IOException {
        String deploy = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintDeploy.java");
        assertEquals(1, count(deploy, "BlueprintRestore.settle("), "写回只有一处调用点：落地钩子");
        assertTrue(count(deploy, "BlueprintRestore.finish(") >= 3,
                "物品直放、加农炮无炮弹、「放完了」三种收尾都落到同一个 finish");
        assertTrue(deploy.contains("player.serverLevel()"), "物品直放的收尾有世界可用");
        assertTrue(deploy.contains("instanceof BlockEntity entity ? entity.getLevel() : null"),
                "加农炮的收尾从方块实体拿世界");
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }

    /** Reads one of the repository's own files, failing loudly rather than quietly skipping the check. */
    private static String read(String... parts) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file, so it must not be skipped");
    }
}

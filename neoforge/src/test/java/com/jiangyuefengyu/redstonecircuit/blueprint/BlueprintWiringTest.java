package com.jiangyuefengyu.redstonecircuit.blueprint;

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
 * The blueprint hooks' wiring: the lines no other test can see, because they are about Create not being installed.
 *
 * <p>Three things here would be invisible in a green build. The mixin config has to be gated by its own plugin and
 * named as a separate file, or a player without Create gets a failed game start instead of an unchanged game. The
 * upload hook has to sit at the <b>close</b> of the stream, because the two statements after it read the file back.
 * And both paths have to end in one funnel, or the instant path and the upload path will drift apart.
 *
 * <p>What it asserts is text: this is a build-time guard, and the Create classes it names are absent from every build
 * this repository can produce without them.
 */
class BlueprintWiringTest {

    private static final List<String> MIXIN_CLASSES = List.of("CreateSchematicExportMixin", "CreateSchematicUploadMixin");

    @Test
    @DisplayName("没装 Create 时：mixin 配置自己的插件把三个钩子全部关掉，而且目标全是字符串")
    void theCreateHooksAreOffWithoutCreate() throws IOException {
        String config = read("src", "main", "resources", "redstonecircuit.create.mixins.json");
        assertTrue(config.contains("\"required\": false"),
                "配置文件本身不是必需的：Create 不在时它连一条都不能生效");
        assertTrue(config.contains("\"plugin\": \"com.jiangyuefengyu.redstonecircuit.mixin.CreateMixinPlugin\""),
                "每个钩子是否生效由插件回答，而不是由 Mixin 去解析一个不存在的目标类");
        for (String mixin : MIXIN_CLASSES) {
            assertTrue(config.contains("\"" + mixin + "\""), mixin + " 必须在配置里列名");
        }
        assertTrue(config.contains("\"client\": [\n    \"CreateSchematicLoaderMixin\"\n  ]"),
                "客户端那一个钩子只在客户端生效：专用服务器上没有本地文件可上传");
        assertFalse(config.contains("\"refmap\""), "没有 refmap 依赖：目标名字都是字符串，没有一个类被编译进来");

        String toml = read("src", "main", "templates", "META-INF", "neoforge.mods.toml");
        assertTrue(toml.contains("config=\"${mod_id}.create.mixins.json\""),
                "FML 得知道这份配置存在，否则钩子一次都不会被读到");

        String plugin = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "CreateMixinPlugin.java");
        assertTrue(plugin.contains("ModList.get() != null && ModList.get().isLoaded(CREATE)"),
                "装没装 Create 问的是加载器的模组表");
        assertTrue(plugin.contains("getResource(CREATE_RESOURCE) != null"),
                "模组表还没填好时问 classpath，而且只是查资源：查一下不会把 Create 的类加载进来");
        assertTrue(plugin.contains("public boolean shouldApplyMixin(String targetClassName, String mixinClassName)"),
                "所有钩子都从这一个问题过");
    }

    @Test
    @DisplayName("三个钩子：目标写成字符串，一个 Create 类型都不许 import")
    void theHooksNameNoCreateType() throws IOException {
        for (String mixin : List.of("CreateSchematicExportMixin", "CreateSchematicUploadMixin",
                "CreateSchematicLoaderMixin")) {
            String source = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                    mixin + ".java");
            assertFalse(source.contains("import com.simibubi"),
                    mixin + " 一个 Create 类型都不许 import：主源集在没有 Create 的机器上也要能编译");
            assertTrue(count(source, "com.simibubi") == 1,
                    mixin + " 里 com.simibubi 只许出现在 @Mixin(targets = ...) 那一行，实际出现 "
                            + count(source, "com.simibubi") + " 次");
            assertTrue(source.contains("require = 0"),
                    mixin + " 的注入点必须允许缺失：Create 改一个方法名只该让蓝图少带元件，不该让游戏起不来");
        }

        String upload = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "CreateSchematicUploadMixin.java");
        assertTrue(upload.contains("target = \"Ljava/io/OutputStream;close()V\"")
                        && upload.contains("shift = At.Shift.AFTER"),
                "上传路径的注入点必须在流关闭之后、读完之前：create 之后紧接着的 finishUpload 与 "
                        + "SchematicItem.create 都会把文件读回去，晚一步就得先把旧内容塞给 Create");
        assertFalse(upload.contains("at = @At(\"TAIL\")"),
                "退回方法末尾就晚了：那样 Create 拿到的还是没写元件的副本");

        String loader = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "CreateSchematicLoaderMixin.java");
        assertTrue(loader.contains("method = \"startNewUpload\", at = @At(\"HEAD\")"),
                "公告必须在 begin 之前发：同一条连接上的包是顺序到达的，服务端在开流前就该知道这只盒子");
    }

    @Test
    @DisplayName("两条落盘路径共用一个漏斗，而且都是先有文件再注入")
    void bothPathsEndInTheSameFunnel() throws IOException {
        String export = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintExport.java");

        assertTrue(export.contains("if (level instanceof ServerLevel serverLevel) {"),
                "服务端才有 store，客户端只能记住盒子");
        assertTrue(count(export, "attachToFile(") >= 3,
                "导出、上传两条路径都必须落到 attachToFile 上，实际调用 " + count(export, "attachToFile(")
                        + " 次（含定义与自检）");
        assertTrue(export.contains("accessor(result, \"file\")"),
                "导出侧拿的是 Create 返回记录里的 file()：那是它刚写完的那个文件");
        assertTrue(export.contains("NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())"),
                "上传侧拿到的是已经写完的字节，只能读回来再补");
        assertTrue(export.indexOf("NbtIo.readCompressed") < export.indexOf("NbtIo.writeCompressed"),
                "先读后写");
        assertTrue(export.indexOf("NbtIo.writeCompressed") < export.indexOf("Files.move("),
                "写完再换名，中途失败不会留下半只蓝图");
        assertTrue(export.indexOf("blueprint.contains(BlueprintComponents.KEY)")
                        < export.indexOf("blueprint.put(BlueprintComponents.KEY"),
                "文件里已经有这一段的就不要再写一遍：单人存档里导出的那一份本来就是对的");

        assertTrue(export.contains("player.getGameProfile().getName()"),
                "上传目标的目录就是 Create 自己的那一个，按玩家名分");
        assertTrue(export.contains("Class.forName(CREATE_PATHS"),
                "而那一个目录是反射问 Create 要的：主源集不许写 import com.simibubi");
        assertTrue(export.contains("!file.startsWith(directory) || !isPlainNbtName(schematic)"),
                "拼出来的路径必须还在那个目录里：客户端给的名字是它可以随便写的东西");
        assertTrue(export.contains("!level.dimension().equals(pending.dimension())"),
                "盒子是在哪个维度量的，就只能在哪个维度导出");
        assertTrue(export.contains("BlueprintLimits.DEFAULT.refuseDistance(player.blockPosition()"),
                "选区离玩家太远就是不导出：这是这个功能唯一能被客户端牵着走的地方");
    }

    @Test
    @DisplayName("协议：新增一个上行的选区公告，版本号跟着升")
    void theAnnouncementIsRegisteredAndTheVersionMoved() throws IOException {
        String network = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "network",
                "RCNetwork.java");
        assertTrue(network.contains("private static final String VERSION = \"15\";"),
                "协议改了就得升版本：老客户端不会知道自己漏了哪一个包");
        assertTrue(network.contains(".playToServer(SchematicSelectionPayload.TYPE, SchematicSelectionPayload.STREAM_CODEC,"),
                "公告是客户端发的，服务端收");
        assertTrue(network.contains("BlueprintExport.limits(player, payload.first(), payload.second()).isPresent()"),
                "服务端在收包时就先按玩家位置判一次，不等文件写完再判");
        assertTrue(network.contains("BlueprintUploadRequests.remember(player.getUUID(), payload.schematic(),"),
                "通过之后才记下来，写文件时按 (玩家, 文件名) 取回");
    }

    @Test
    @DisplayName("数据格式：一个带命名空间的顶层键，版本号自成一格")
    void theSectionIsOneNamespacedTopLevelKey() throws IOException {
        String codec = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "blueprint",
                "BlueprintComponents.java");
        assertTrue(codec.contains("public static final String KEY = \"redstonecircuit\";"),
                "顶层键只用我们自己的模组名，别的 add-on 不会来抢");
        assertTrue(codec.contains("public static final int VERSION = 1;"),
                "自己的版本号与文件自己的 DataVersion 无关：Create 升一次不该让元件作废");
        for (String key : List.of("\"nodes\"", "\"clocks\"", "\"extenders\"", "\"p\"", "\"component\"")) {
            assertTrue(codec.contains("= " + key + ";"), "段里少了 " + key + " 这个键");
        }
        assertTrue(codec.contains("public static Section readFile(CompoundTag blueprint)"),
                "放置侧只需要一个入口：给整份文件，换回段或 null");
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

    /**
     * Reads one of the repository's own files, failing loudly rather than quietly skipping the check. Tries both
     * layouts the harness may run from: the project directory and the repository root.
     */
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

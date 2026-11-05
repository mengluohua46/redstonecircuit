package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 配置界面和 ModMenu 钩子, 按文本钉住: 这一页要在游戏里才存在, 而这里跑的是服务端那套测试, 界面永远加载不到。
 *
 * <p>所以真正会坏而测试看不见的, 恰好是下面这几条:
 *
 * <ul>
 *   <li>行是不是还从 {@code RCConfig.keys()} 来 - 自己列一份清单是这一页最容易犯的错, 新加的设置会悄悄消失;
 *   <li>走表的那几个键是不是都真的在 {@code RCConfig.DEFAULTS} 里 - 拼错的键没有任何表现, 只是那一行不出现;
 *   <li>语言文件里的键是不是都还在 - 按下标拼出来的键, 少一条就是屏幕上的一串英文键名;
 *   <li>钩子是不是还实现着 {@code ModMenuApi} 并交出来的是一页 {@code ConfigScreen}。
 * </ul>
 *
 * <p>另外两条整体规矩也在这里钉: 这一页不许出现 {@code net.neoforged} 的导入, 客户端注解要用 Fabric 自己的
 * {@code @Environment(EnvType.CLIENT)}, 而不是 NeoForge 的 {@code @OnlyIn(Dist.CLIENT)}。
 */
class ConfigScreenWiringTest {

    /** 走表的每一个键: {@code CYCLES.put("名字", ...)}。 */
    private static final Pattern CYCLE_KEY = Pattern.compile("CYCLES\\.put\\(\"([A-Za-z]+)\"");

    private static String source(String... parts) throws IOException {
        return Files.readString(Path.of("src", parts), StandardCharsets.UTF_8);
    }

    private static String screen() throws IOException {
        return source("main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client", "ConfigScreen.java");
    }

    private static String hook() throws IOException {
        return source("main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "RedstoneCircuitModMenu.java");
    }

    private static String config() throws IOException {
        return source("main", "java", "com", "jiangyuefengyu", "redstonecircuit", "RCConfig.java");
    }

    private static String lang(String file) throws IOException {
        return source("main", "resources", "assets", "redstonecircuit", "lang", file);
    }

    @Test
    @DisplayName("这一页是一页 Screen, 行从 RCConfig.keys() 来, 控件照默认值的类型画")
    void theRowsComeFromTheConfigItself() throws IOException {
        String screen = screen();

        assertTrue(screen.contains("public final class ConfigScreen extends Screen {"), "它就是一页界面, 不是别的");
        assertTrue(screen.contains("RCConfig.keys()"), "行是配置表给的: 自己再写一份清单, 新加的设置就会悄悄消失");
        assertTrue(screen.contains("RCConfig.current(key) == null"), "读不出来的键(列表型选项)跳过");
        assertTrue(screen.contains("Object fallback = RCConfig.defaultValue(key);"),
                "画什么由默认值的类型决定 - 文件里的值是可以被写坏的, 界面不能跟着换一套控件");
        assertTrue(screen.contains("RCConfig.set("), "每一次改动都写回去(JsonConfig.put 自己会存盘)");
        assertTrue(screen.contains("this.rebuildWidgets();"),
                "只给看得见的那几行建控件, 所以滚动就是重新摆一遍, Tab 也不会跑到屏幕外面");
    }

    @Test
    @DisplayName("走表的常量一个都没少, 而且表里的每个键都真的在 RCConfig.DEFAULTS 里")
    void everyCycleTableEntryExistsInTheDefaults() throws IOException {
        String screen = screen();

        // 三个显示模式的常量名.
        assertTrue(screen.contains("values(\"GOGGLES\", \"ALWAYS\", \"OFF\")"), "显示模式: 三个常量");
        // 两个 alpha 的档: 玩家看得出来的是这几档.
        assertTrue(screen.contains("values(0, 50, 100, 150, 170, 200, 255)"), "alpha: 0 到 255 的几档");
        // 调色板: 渲染器把颜色当十六进制串读, 所以存的是字符串本身.
        assertTrue(screen.contains("\"9FB6FF\", \"E03020\", \"FF8C00\", \"7FE070\", \"F0E060\", \"FFFFFF\", \"000000\""),
                "调色板: 语言文件的提示里点到的就是这七个");
        assertTrue(screen.contains("values(1, 2, 4, 8, 16, 32)"), "cascade: 2 的幂, 1 是关");

        Set<String> keys = new LinkedHashSet<>();
        Matcher found = CYCLE_KEY.matcher(screen);
        while (found.find()) {
            keys.add(found.group(1));
        }
        assertEquals(Set.of("hostOverlayMode", "hostOverlayAlpha", "hostOverlayColor", "hostOverlayFrameAlpha",
                "hostOverlayFrameColor", "superconductorFrameColor", "solverCascadeLimit"), keys,
                "走表的就是这七个: 多一个少一个都说明表被改过");

        String config = config();
        for (String key : keys) {
            assertTrue(config.contains("DEFAULTS.put(\"" + key + "\""),
                    key + " 不在 RCConfig.DEFAULTS 里: 这一行永远不会出现, 而屏幕上什么都不会说");
        }
    }

    @Test
    @DisplayName("名字和提示都是一条语言文件的键: 提示是名字加 .tooltip")
    void theTooltipKeyIsTheNameKeyPlusTooltip() throws IOException {
        String screen = screen();

        assertTrue(screen.contains("\"redstonecircuit.configuration.\" + key"), "名字的键");
        assertTrue(screen.contains("nameKey(key) + \".tooltip\""), "提示的键就是名字加 .tooltip, 只在这一个地方拼");
        assertTrue(screen.contains("Tooltip.create("), "悬停时由这一行的控件自己报出这条提示");
        assertTrue(screen.contains("this.setTooltipForNextRenderPass(hint);"),
                "只用键盘的玩家也要能看到: Tab 选中的那行, 悬停之外再补一次");
        assertTrue(screen.contains("Component.translatable(\"gui.done\")"), "底部那个按钮是原版的 Done");
    }

    @Test
    @DisplayName("显示模式的三条标签来自语言文件里各自的键")
    void theModeLabelsAreTheirOwnLanguageKeys() throws IOException {
        String screen = screen();

        assertTrue(screen.contains("\"redstonecircuit.configuration.hostOverlayMode.\" + text"),
                "GOGGLES / ALWAYS / OFF 各有各的一句话, 不是一个数字");
        assertTrue(screen.contains("MODE_LABELS = Set.of(\"GOGGLES\", \"ALWAYS\", \"OFF\")"),
                "要认出这三个常量名, 才不会把颜色也当成常量去查翻译");

        for (String file : new String[] {"en_us.json", "zh_cn.json"}) {
            String lang = lang(file);
            for (String mode : new String[] {"GOGGLES", "ALWAYS", "OFF"}) {
                assertTrue(lang.contains("\"redstonecircuit.configuration.hostOverlayMode." + mode + "\""),
                        file + " 少了 " + mode + " 的标签");
            }
        }
    }

    @Test
    @DisplayName("这一页用到的语言文件键都还在: 标题、分区标题, 以及每一个设置的名字和提示")
    void everyKeyTheScreenAsksForIsInTheLanguageFiles() throws IOException {
        String screen = screen();
        assertTrue(screen.contains("\"redstonecircuit.configuration.title\""), "标题");
        assertTrue(screen.contains("redstonecircuit.configuration.section.redstonecircuit.common.toml.title"),
                "分区标题: 语言文件里的那条 section 键");

        String config = config();
        for (String file : new String[] {"en_us.json", "zh_cn.json"}) {
            String lang = lang(file);
            assertTrue(lang.contains("\"redstonecircuit.configuration.title\""), file + " 少了界面的标题");
            for (String key : configKeys(config)) {
                assertTrue(lang.contains("\"redstonecircuit.configuration." + key + "\""),
                        file + " 少了 " + key + " 的名字");
                assertTrue(lang.contains("\"redstonecircuit.configuration." + key + ".tooltip\""),
                        file + " 少了 " + key + " 的提示");
            }
        }
    }

    /** {@code DEFAULTS.put("键", ...)} 里的那些键, 也就是界面会问语言文件要名字的那些。 */
    private static Set<String> configKeys(String config) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher found = Pattern.compile("DEFAULTS\\.put\\(\"([A-Za-z]+)\"").matcher(config);
        while (found.find()) {
            keys.add(found.group(1));
        }
        return keys;
    }

    @Test
    @DisplayName("钩子实现 ModMenuApi, 交出来的是一页 ConfigScreen, 而 ModMenu 本来就不在依赖里")
    void theHookHandsModMenuAScreen() throws IOException {
        String hook = hook();

        assertTrue(hook.contains("public final class RedstoneCircuitModMenu implements ModMenuApi {"),
                "不实现这个接口, ModMenu 就没有理由给它一个按钮");
        assertTrue(hook.contains("public ConfigScreenFactory<?> getModConfigScreenFactory() {"), "签名是 ModMenu 的那一个");
        assertTrue(hook.contains("new ConfigScreen(parent)"), "交出来的是这一页, 而且带着父界面(关掉时回得去)");
        assertTrue(hook.contains("import com.terraformersmc.modmenu.api.ConfigScreenFactory;"), "编译期的那个 jar");
        assertTrue(hook.contains("@Environment(EnvType.CLIENT)"), "只在客户端: 服务端没有界面可言");

        String mod = source("main", "resources", "fabric.mod.json");
        assertTrue(mod.contains("com.jiangyuefengyu.redstonecircuit.client.RedstoneCircuitModMenu"),
                "少了这行入口点, 上面这个类就是死代码");
        assertTrue(mod.contains("\"modmenu\": ["), "ModMenu 按这个名字来取");
    }

    @Test
    @DisplayName("没装 ModMenu 的人不受影响: 这个类只在编译期存在, 而且一个 NeoForge 的导入都没有")
    void noNeoForgeLeftoversAndNoHardDependency() throws IOException {
        String hook = hook();
        String screen = screen();
        for (String text : new String[] {hook, screen}) {
            assertFalse(text.contains("net.neoforged"), "Fabric 这一侧不许再出现 NeoForge 的导入");
            assertFalse(text.contains("OnlyIn"), "客户端注解用 Fabric 自己的 @Environment");
            assertFalse(text.contains("Dist.CLIENT"), "同上: EnvType.CLIENT");
        }
        // 编译期的 jar, 而不是 depends: 装不装 ModMenu, 只是有没有那个按钮.
        assertTrue(Files.readString(Path.of("build.gradle"), StandardCharsets.UTF_8).contains("modCompileOnly files(\"libs/modmenu-11.0.5.jar\")"),
                "ModMenu 是 modCompileOnly 的: 没装它的玩家只是看不到按钮");

        String mod = source("main", "resources", "fabric.mod.json");
        String depends = mod.substring(mod.indexOf("\"depends\""), mod.indexOf("\"suggests\""));
        assertFalse(depends.contains("modmenu"), "ModMenu 不能进 depends: 那就成了'必须装'");
    }
}

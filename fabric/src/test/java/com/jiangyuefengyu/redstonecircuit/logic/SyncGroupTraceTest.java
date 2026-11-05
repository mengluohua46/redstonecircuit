package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>组 pass 的诊断: 默认一条都不产生, 打开时一行一条.</b>
 *
 * <p>为什么这一条非要钉死: 默认配置下, 一次构建里刷屏的 trace 是没人会去读的日志, 而一个关不掉的
 * 开关会让"看一次"变成"清一次日志"。所以两件事各钉一遍:
 *
 * <ul>
 *   <li><b>默认关闭是形状上的</b>: {@link SyncGroups#traceOf(boolean)} 在开关不成立时答 {@code null}, 而组 pass 的
 *       每一个输出都挂在这个对象上 - 没有对象, 谁都写不出一行;</li>
 *   <li><b>开关只有一处判据</b>: {@code RCConfig.traceSyncGroup()} 在组 pass 里只被读一次, 而且 {@code RCConfig.LOGGER}
 *       只出现在 {@code GroupTrace} 里。</li>
 * </ul>
 *
 * <p>还钉住一件不能破的事: 这个开关<b>不是一个 {@code ModConfigSpec} 选项</b>。配置屏上的每个键都必须有两份
 * {@code lang} 文件里的一条名字与一条 tooltip ({@code ConfigurationLangTest}), 而这一轮不动 lang - 于是它走
 * {@code debugLog} 已经在用的那条路 (一个系统属性), 而这里钉住"没有人偷偷加一个 {@code define}": 那会让构建里
 * 多一条红。
 */
class SyncGroupTraceTest {

    private static final CarrierId MACHINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000009701"));

    private static final BlockPos LOCAL = new BlockPos(4, 1, -3);
    private static final BlockPos WORLD = new BlockPos(120, 64, -40);

    // ------------------------------------------------------ 1. 开关的形状 --

    @Test
    @DisplayName("关着时没有对象, 开着时有一个: '默认配置下不产生日志'因此是形状上的, 不是一句约定")
    void theSwitchDecidesWhetherThereIsAnythingToWrite() {
        assertNull(SyncGroups.traceOf(false), "开关不成立就什么都没有: 一行日志都写不出来");
        assertNotNull(SyncGroups.traceOf(true), "开关成立时才有那个写行的人");
    }

    @Test
    @DisplayName("开关走系统属性, 不占配置屏的一个键: 那样才不必动 lang")
    void theSwitchIsASystemPropertyAndNotAConfigEntry() throws IOException {
        String config = config();
        String accessor = bodyOf(config, "public static boolean traceSyncGroup()");

        assertTrue(accessor.contains("Boolean.getBoolean(\"redstonecircuit.traceSyncGroup\")"),
                "问的是'这一次运行要不要': -Dredstonecircuit.traceSyncGroup=true");
        assertEquals(0, occurrences(config, ".define(\"traceSyncGroup\""),
                "配置屏上的每个键都得在两份 lang 里有一条名字与一条 tooltip (ConfigurationLangTest); "
                        + "这一轮不动 lang, 于是它不许是一个 ModConfigSpec 选项");
    }

    // --------------------------------------------------- 2. 输出里有什么 --

    @Test
    @DisplayName("一个成员在哪: 世界坐标 / (载体 shortId, 机器里的局部坐标) 都写出来")
    void aMemberIsNamedByWhereItActuallyIs() {
        String carried = SyncGroups.GroupTrace.describe(SyncGroups.Spot.carried(MACHINE, LOCAL));
        assertTrue(carried.contains(MACHINE.shortId()), "载体要能一眼认出来");
        assertTrue(carried.contains(LOCAL.toShortString()), "机器里的局部坐标, 不是任何世界坐标");
        assertFalse(carried.contains(WORLD.toShortString()));

        String standing = SyncGroups.GroupTrace.describe(SyncGroups.Spot.standing(WORLD));
        assertTrue(standing.contains(WORLD.toShortString()), "立在世界里的就是那个世界坐标");
    }

    @Test
    @DisplayName("掩码写成哪几面: 顺序固定, 空掩码写成'-'")
    void theMaskIsWrittenAsTheFacesThatHaveInput() {
        assertEquals("-", SyncGroups.GroupTrace.facesOf(0), "一面都没有: 就是'-'");
        assertEquals("EAST", SyncGroups.GroupTrace.facesOf(SyncTorchRule.bit(Direction.EAST)));
        String two = SyncGroups.GroupTrace.facesOf(
                SyncTorchRule.bit(Direction.DOWN) | SyncTorchRule.bit(Direction.EAST));
        assertTrue(two.contains("DOWN") && two.contains("EAST"),
                "每一位都要写出来, 顺序按 Direction.values(): " + two);
    }

    // ------------------------------------------------------- 3. source wiring --

    @Test
    @DisplayName("开关在组 pass 里只读一次, 而且在轮次循环之前")
    void theSwitchIsReadOncePerTick() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String tick = bodyOf(groups, "public static void tick(ServerLevel level)");

        assertEquals(1, occurrences(groups, "RCConfig.traceSyncGroup()"),
                "一处判据: 散在调用点就迟早出现'一半开着'");
        assertTrue(tick.contains("RCConfig.traceSyncGroup()"), "它是这一 tick 的开关, 于是活在 tick 里");
        assertTrue(tick.indexOf("RCConfig.traceSyncGroup()") < tick.indexOf("for (int pass"),
                "在轮次循环之前问一次: 每一轮再判一次就是四个判据");
        assertEquals(1, occurrences(groups, "new GroupTrace("),
                "诊断对象只在 traceOf 里建一次, 于是'没有对象'与'关着'是同一件事");
    }

    @Test
    @DisplayName("组 pass 的日志只从 GroupTrace 里写出去, 一行一条, 前缀固定")
    void everyLineComesFromTheDiagnosticObject() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String trace = bodyOf(groups, "static final class GroupTrace");

        assertEquals(occurrences(groups, "RCConfig.LOGGER"), occurrences(trace, "RCConfig.LOGGER"),
                "组 pass 的日志只出现在诊断类里: 别处写一行, 关着开关也照样刷屏");
        assertTrue(trace.contains("RCConfig.LOGGER.info(\"[redstonecircuit] syncGroup {}\", body)"),
                "一行一条, 前缀固定, 于是复现后 grep syncGroup 就能按趟读下来");
    }

    @Test
    @DisplayName("行里有什么: 形态、掩码与 LIT、六面的两份读数与跳过理由、答案与写回")
    void theLinesCarryWhatTheAuthorNeedsToRead() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String trace = bodyOf(groups, "static final class GroupTrace");

        assertTrue(trace.contains("form="), "内嵌形态还是世界形态");
        assertTrue(trace.contains("support=") && trace.contains("mask=0x") && trace.contains("lit="),
                "吸附面朝向、上一 tick 的掩码、此刻亮没亮");
        assertTrue(trace.contains("storage="), "五面要多问的那一份存储是哪一种 (none / level / carrier-view)");
        assertTrue(trace.contains("skipped="), "被跳过的那一面要留一行并写出理由");
        // 措辞住在同一个文件的理由助手那里 (一行里写出的是这两种之一), 于是"这一面为什么被跳过"分得开:
        // 是"我们自己在往它输出"(哪一半: tick 开头 / 此刻 / 两半都是) 还是"那一格还没加载"。
        assertTrue(groups.contains("outputting-into-that-face") && groups.contains("drawn-not-loaded"),
                "否则'六面里少一行'与'读了答 0'分不开");
        assertTrue(trace.contains("drawn=") && trace.contains("structure="),
                "画出来的那一格与机器自己那一份各答多少");
        assertTrue(trace.contains("power=") && trace.contains("inputs=0x") && trace.contains("written="),
                "这一组这一趟算出多少, 以及写回真的改了什么没有");
    }

    // ------------------------------------------------------------- helpers --

    private static String config() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "RCConfig.java");
    }

    private static String main(String area, String name) throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", area, name);
    }

    private static String read(String... parts) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body found for '" + signature + "'");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after '" + signature + "'");
    }

    private static int occurrences(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }
}

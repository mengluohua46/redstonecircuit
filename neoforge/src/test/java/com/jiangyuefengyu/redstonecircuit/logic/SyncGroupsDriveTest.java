package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * <b>"我们正在往哪几面输出"由两个时刻合起来答: (b) tick 开头那一份是这趟的常量, (a) 此刻那一份每轮现读一次.</b>
 *
 * <p>两半各修一个方向的自锁: 少了 (b), 第 1 轮写成灭之后第 2 轮去听自己上一 tick 打出去、邻居还没释放的那几面;
 * 少了 (a), 第 1 轮点亮之后第 2 轮去听邻居在同一 tick 里刚亮起来的那几面。判据都只有
 * {@link SyncTorchRule#listensTo} 那一句, {@link SyncGroups.Drives#listensTo} 只做一件事: 两半取与。
 *
 * <p>两半各自钉住一个方向的环路, 而两半都不能省: 只抓不读 (只有 (b)), 一趟 pass 的第 1 轮把 LIT 写成 false 之后,
 * 第 2 轮现读得到"我什么都没输出", 于是去听自己上一 tick 刚打出去、邻居还来不及释放的那几面 (世界求解在组 pass 之后才
 * 跑), 读回自己的 15, 掩码置位, OR 把整组重新点亮 - 地面那支因此亮熄亮熄; 只读不抓 (只有 (a)), 同一 tick 里
 * 第 1 轮刚点亮、邻居已经跟着亮起来, 第 2 轮同样把自己打出去的 15 读成输入 - 旁边那根粉跟着亮却让两支一起锁死。
 *
 * <p>这里全是不需要 {@code ServerLevel} 的纯值: 一份驱动事实就是一个(掩码, LIT), 而写回改的是 store / 槽位 / 那一格
 * 方块, 改不动(tick 开头)抓下来的那一份 - 这就是修复的形状, 不是一句约定。
 */
class SyncGroupsDriveTest {

    private static final BlockPos A = new BlockPos(6, 64, -2);

    private static final SyncGroups.Spot SPOT = SyncGroups.Spot.standing(A);

    /** 上一 tick: 亮着, 一面输入都没有。吸附面朝下, 于是其余五面都在输出。 */
    private static SyncGroups.Drives litDrivingFiveFaces() {
        return new SyncGroups.Drives(Map.of(SPOT, new SyncGroups.Drive((byte) 0, true)));
    }

    /** 一份手写的驱动集: 一个成员, 两份事实。 */
    private static SyncGroups.Drives drivesOf(byte inputs, boolean lit) {
        Map<SyncGroups.Spot, SyncGroups.Drive> bySpot = new LinkedHashMap<>();
        bySpot.put(SPOT, new SyncGroups.Drive(inputs, lit));
        return new SyncGroups.Drives(bySpot);
    }

    // ------------------------------------------------- 1. 逐面语义 (不许变) --

    @Test
    @DisplayName("亮着时: 驱动集里那几面不听, 其余面听, 吸附面永远听")
    void theDrivenFacesAreNotListenedTo() {
        SyncGroups.Drive drive = litDrivingFiveFaces().at(SPOT);

        assertTrue(drive.lit(), "上一 tick 它是亮的");
        assertEquals(0, drive.inputs(), "一面输入都没有");
        for (Direction side : Direction.values()) {
            if (side == Direction.DOWN) {
                assertTrue(drive.listensTo(Direction.DOWN, side),
                        "吸附面不输出, 于是它永远听 - 它是我们的来源, 不听就永远灭不了");
            } else {
                assertFalse(drive.listensTo(Direction.DOWN, side),
                        side + " 上一 tick 正在输出: 它的读数可能就是我们自己打出去的那一份");
            }
        }
    }

    @Test
    @DisplayName("掩码里的那一面继续听 (它本来就不输出), 其余驱动面照旧不听")
    void anInputFaceKeepsListening() {
        byte east = SyncTorchRule.bit(Direction.EAST);
        SyncGroups.Drive drive = drivesOf(east, true).at(SPOT);

        assertTrue(drive.listensTo(Direction.DOWN, Direction.EAST),
                "东面已经是输入: 我们不再往它输出, 于是它照旧被听着 - 外部信号撤掉时才能跟着灭");
        assertFalse(drive.listensTo(Direction.DOWN, Direction.UP),
                "其余还在输出的那几面照旧不听");
        assertEquals(east, drive.inputs(), "逐面语义一位一面, 不改");
    }

    // ---------------------------------- 2. 写回改不动这一趟的依据 --

    @Test
    @DisplayName("同一 tick 内第 1 轮把答案从亮改灭, 第 2 轮仍然不听自己上一 tick 正在驱动的那几面")
    void aWriteInsideTheTickCannotChangeTheBasisOfListensTo() {
        SyncGroups.Drives drives = litDrivingFiveFaces();

        // 第 1 轮: 组里没有别人收到信号, 于是答案从 15 掉到 0, 写回把 LIT 写成 false。写回改的是 store / 槽位 /
        // 那一格方块; 驱动集是抓来的那一份, 而它不该被搬动 - 这一趟对每个成员只有一个"上一 tick 在驱动哪几面"。
        for (int round = 1; round <= SyncTorchRule.MAX_PASS_ITERATIONS; round++) {
            SyncGroups.Drive drive = drives.at(SPOT);
            assertTrue(drive.lit(), "第 " + round + " 轮的依据仍然是上一 tick 的 LIT");
            assertFalse(drive.listensTo(Direction.DOWN, Direction.WEST),
                    "第 " + round + " 轮仍然不听西面: 我们上一 tick 正往那一面输出, 邻居还没释放");
            assertEquals(0, drive.inputs(), "掩码也是上一 tick 的那一份, 不是本轮刚写下去的那一份");
        }

        // 反面对照, 也就是这个缺陷本身: 依据一旦跟着写回走, 灭着就"六面都听", 于是那五面读到的正是我们上一 tick
        // 打出去、世界还来不及释放的 15 -> 掩码置位 -> OR 把整组重新点亮 -> 下一轮又变。这一句钉在这里, 是为了让
        // 上面那一条不会悄悄变回它。
        assertTrue(SyncTorchRule.listensTo((byte) 0, Direction.DOWN, false, Direction.WEST),
                "灭着的时候六面都听: 这正是'依据跟着写回走'会把我们自己的输出读成输入的地方");
    }

    @Test
    @DisplayName("抓下来的那一份是拷贝: 谁在外面改那张表, 都改不动这一趟的依据")
    void theCaptureIsNotAWindowOnAnythingMutable() {
        Map<SyncGroups.Spot, SyncGroups.Drive> mutable = new LinkedHashMap<>();
        mutable.put(SPOT, new SyncGroups.Drive((byte) 0, true));
        SyncGroups.Drives drives = new SyncGroups.Drives(mutable);

        // 第 1 轮之后写回把那一支写成灭的: 世界里那一份已经变了 (这正是现读会错的来源)。
        mutable.put(SPOT, new SyncGroups.Drive(SyncTorchRule.bit(Direction.EAST), false));

        assertTrue(drives.at(SPOT).lit(), "这一趟的依据是抓来的那一份, 不是任何一张还会变的表");
        assertEquals(0, drives.at(SPOT).inputs(), "掩码同理");
    }

    @Test
    @DisplayName("这一趟没抓到的地址答'什么都没驱动': 六面都听, 而它自己一个 15 都没打出去")
    void anAddressCaughtAfterTheSnapshotDrivesNothing() {
        SyncGroups.Drive dark = new SyncGroups.Drives(Map.of()).at(SPOT);

        assertFalse(dark.lit(), "这一趟开始之前它不在名单里, 于是上一 tick 它是灭的");
        assertEquals(0, dark.inputs());
        for (Direction side : Direction.values()) {
            assertTrue(dark.listensTo(Direction.DOWN, side),
                    side + ": 灭着的火把读到的信号不可能是自己造的 - 于是缺一份也不许凭空关掉一面");
        }
    }

    // ---------------------------------- 2b. 两半: (b) tick 开头 / (a) 此刻 ----

    /** tick 开头的那一份: 灭着, 一面都没驱动。 */
    private static final SyncGroups.Drive NOTHING_DRIVEN = new SyncGroups.Drive((byte) 0, false);

    @Test
    @DisplayName("(b) tick 开头那一份: 亮着且那一面不在掩码里 - 那一刻我们在驱动它, 于是不听")
    void theTickStartHalfClosesTheFacesWeWereDriving() {
        SyncGroups.Drive start = new SyncGroups.Drive((byte) 0, true);
        SyncGroups.Drive now = NOTHING_DRIVEN;

        assertFalse(SyncGroups.Drives.listensTo(start, now, Direction.DOWN, Direction.WEST),
                "(b): tick 开头我们正往西面输出, 邻居还来不及释放 - 这一轮不许去听它");
        assertTrue(now.listensTo(Direction.DOWN, Direction.WEST),
                "反面对照: 只看此刻那一份, 灭着就是六面都听 - 这正是上一轮修掉的那个自锁");
        assertTrue(SyncGroups.Drives.listensTo(start, now, Direction.DOWN, Direction.DOWN),
                "吸附面永远听: 它是我们的来源, 不听就永远亮不起来");
    }

    @Test
    @DisplayName("(a) 此刻那一份: 亮着且那一面不在掩码里 - 此刻我们在驱动它, 于是不听")
    void theNowHalfClosesTheFaceWeJustStartedDriving() {
        byte down = SyncTorchRule.bit(Direction.DOWN);
        SyncGroups.Drive start = NOTHING_DRIVEN;
        SyncGroups.Drive now = new SyncGroups.Drive(down, true);

        assertFalse(SyncGroups.Drives.listensTo(start, now, Direction.DOWN, Direction.WEST),
                "(a): 此刻亮着且西面不在掩码里 = 正在往它输出 - 邻居刚亮起来的那份 15 不是我们的输入");
        assertTrue(start.listensTo(Direction.DOWN, Direction.WEST),
                "反面对照: 只看 tick 开头那一份, 那里还没有西面在输出 - 那一份补不上这半, 只有此刻这一次读能补");
        SyncGroups.Drive latched = new SyncGroups.Drive((byte) (down | SyncTorchRule.bit(Direction.EAST)), true);
        assertTrue(SyncGroups.Drives.listensTo(start, latched, Direction.DOWN, Direction.EAST),
                "同一份'此刻'里掩码内的那一面照旧听: 它本来就不输出, 读到的一定是外面的信号");
    }

    @Test
    @DisplayName("本缺陷的回归: 同一 tick 内第 1 轮点亮之后, 第 2 轮不听它刚刚开始驱动的那几面")
    void aLightUpInsideTheTickClosesTheFacesItJustStartedDriving() {
        // tick 开头: 灭着, 一面都没输出 - 抓下来的那一份里六面都听。
        SyncGroups.Drive start = NOTHING_DRIVEN;
        // 第 1 轮: 吸附面读到信号, 掩码置位并把 LIT 写成 true; 旁边那根粉在同一 tick 里跟着亮起来, 于是第 2 轮
        // 从它那里读到的是我们自己打出去的 15。
        byte mask = SyncTorchRule.bit(Direction.DOWN);
        for (int round = 1; round <= SyncTorchRule.MAX_PASS_ITERATIONS; round++) {
            SyncGroups.Drive now = new SyncGroups.Drive(mask, true);
            assertFalse(SyncGroups.Drives.listensTo(start, now, Direction.DOWN, Direction.EAST),
                    "第 " + round + " 轮: 此刻我们正往东面输出, 那根粉的 15 是我们自己打出去的");
            assertEquals(mask, now.inputs(), "此刻那一份就是成员自己的存储, 与 tick 开头那一份各读一次");
        }
        assertTrue(start.listensTo(Direction.DOWN, Direction.EAST),
                "反面对照: 抓下来的那一份里东面还什么都没驱动 - 只靠它就会把那根粉读成输入");
    }

    // --------------------------------------------------- 3. 源码接线: 抓一次 --

    @Test
    @DisplayName("驱动集在轮次循环之前只抓一次: 轮次里、以及六面读数那一层, 都没有第二次抓取")
    void theDrivesAreCapturedOnceBeforeTheRoundLoop() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String tick = bodyOf(groups, "public static void tick(ServerLevel level)");

        assertEquals(1, occurrences(tick, "Drives.capture("), "一趟 tick 只能抓一次");
        assertTrue(tick.indexOf("Drives.capture(") < tick.indexOf("for (int pass"),
                "而且是在轮次循环之前抓的: 抓在循环里就是每一轮一份新的 LIT, 正是这个缺陷");

        String runPass = bodyOf(groups, "private static boolean runPass(");
        assertEquals(0, occurrences(runPass, "Drives.capture("), "轮次之间不许再抓");
        assertTrue(runPass.contains("askAt(level, store, entry.getValue(), drives, inquiry,"),
                "它把抓下来的那一份传下去, 于是四轮看的是同一份");

        String askAt = bodyOf(groups, "private static int askAt(");
        assertEquals(0, occurrences(askAt, "Drives.capture("), "六面的读数也不许抓");
        assertTrue(askAt.contains("drives.at(spot)"), "它读的就是抓下来的那一份");
        assertEquals(1, occurrences(askAt, "driveAt("),
                "此刻那一份, 一个成员只读一次 (读法与抓那一份是同一句, 见 driveAt)");
        assertTrue(askAt.indexOf("driveAt(") < askAt.indexOf("for (Direction side : Direction.values())"),
                "而且是在六面循环之前: 每面读一次就是六个不同的时刻");
        assertEquals(0, occurrences(askAt, "syncTorchInputsAt"), "掩码不许在六面循环里逐面现读");
        assertEquals(0, occurrences(askAt, "getValue(SyncTorchBlock.LIT)"), "LIT 同理");
        assertTrue(askAt.contains("!Drives.listensTo(start, now, support, side)"),
                "两半合起来的那一句只有一处: Drives#listensTo -> SyncTorchRule#listensTo");
    }

    // ------------------------------------------------------------- helpers --

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

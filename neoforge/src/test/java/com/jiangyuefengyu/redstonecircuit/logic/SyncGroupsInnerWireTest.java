package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>方块里的一根线也要走</b>.
 *
 * <p>摆法: 方块内放同步火把, 相邻方块内红石粉, 红石粉上方红石灯. 那根粉是 {@code Slot} 形态的元件, 不是世界里的
 * 原版粉方块: 它经 {@code BlockStateSignalMixin} 对<b>宿主方块</b>答满强度 15, 于是走线只认方块状态时它"不是线" -
 * 按"说明不了就不吞"被当成一个外部源, 掩码置位, 自锁。这一轮把"线"扩成两种形态: 世界里的粉, 以及宿主方块里那一个
 * {@code DUST} / {@code SUPERCONDUCTOR} 的元件。
 *
 * <p>纯值那几条: 走线要问的三件事照旧由调用方交进来 ({@link SyncGroups.WireLine}), 于是"方块里的一根线"在不需要方块
 * 注册表的地方也能钉住: <b>环</b> (线里没有外部源) ⇒ 这份读数不算输入; <b>真源</b> (线的另一头有拉杆/红石块) ⇒ 仍然
 * 算输入; <b>到上限</b> ⇒ 算输入; <b>非线邻居</b> ⇒ 按读数算 (它只被读, 不被走)。
 */
class SyncGroupsInnerWireTest {

    /** 西面那一格: 宿主方块 + 里面一根粉 - 它答的 15 就是这一面读到的 15。 */
    private static final BlockPos DUST_HOST = new BlockPos(-1, 0, 0);

    /** 同一根线往西再一格: 另一个宿主里的粉 (方块内的线可以不止一格)。 */
    private static final BlockPos DUST_HOST_FAR = new BlockPos(-2, 0, 0);

    /** 非线邻居: 世界里的中继器 (会读会答, 但它不是线)。 */
    private static final BlockPos REPEATER = new BlockPos(-1, 0, -1);

    // ------------------------------------------ 1. 方块里的一根线: 那是回声 --

    @Test
    @DisplayName("火把旁边是方块内的一根粉, 粉的宿主答 15 而线里没有外部源 ⇒ 那一面不算输入")
    void aLineInsideAHostIsOursAlone() {
        FakeInnerLine line = new FakeInnerLine().line(DUST_HOST, DUST_HOST_FAR);

        boolean ours = SyncGroups.fedByGroupOnly(line, DUST_HOST, SyncGroups.MAX_WIRE_CELLS);

        assertTrue(ours, "两格方块内的粉上都只有我们自己打进去的那份电: 这是环, 不是输入");
        assertTrue(line.walked.contains(DUST_HOST) && line.walked.contains(DUST_HOST_FAR),
                "两格都要走: 只看读到的这一格, 一根两格的线就会把第二格漏掉");
        assertEquals(0, SyncGroups.credited(15, ours),
                "于是西面读到的那份 15 进不了掩码 - 那一面在这里断开");
    }

    @Test
    @DisplayName("同一根方块内的线, 另一头有一支拉杆/红石块 ⇒ 仍然算输入, 外面的电照样点亮整组")
    void aLineFedFromOutsideIsStillAnInput() {
        FakeInnerLine line = new FakeInnerLine()
                .line(DUST_HOST, DUST_HOST_FAR)
                .fedBy(DUST_HOST_FAR);

        boolean ours = SyncGroups.fedByGroupOnly(line, DUST_HOST, SyncGroups.MAX_WIRE_CELLS);

        assertFalse(ours, "线的另一头有一个本组之外的源: 这份电不归我们解释");
        assertTrue(line.walked.contains(DUST_HOST_FAR), "而且要走到那一头才看得见它 - 这是\"走完整根线\"这句话的样子");
        assertEquals(15, SyncGroups.credited(15, ours), "于是它照旧进掩码: 拉杆/红石块/另一支火把仍然点亮整组");
    }

    @Test
    @DisplayName("方块内的线比走线上限还长 ⇒ 说不清 ⇒ 当外部 (宁可留一个真输入, 也不许吞掉真的)")
    void aLineTooLongToWalkKeepsTheInput() {
        FakeInnerLine line = new FakeInnerLine();
        BlockPos[] cells = new BlockPos[SyncGroups.MAX_WIRE_CELLS + 2];
        for (int step = 0; step < cells.length; step++) {
            cells[step] = new BlockPos(-1 - step, 0, 0);
        }
        line.line(cells);

        assertFalse(SyncGroups.fedByGroupOnly(line, cells[0], SyncGroups.MAX_WIRE_CELLS),
                "走到上限还没找到来源, 不等于\"找到了没有\": 这一支答 false (算输入)");
        assertEquals(15, SyncGroups.credited(15, false));
    }

    // -------------------------------------------- 2. 非线邻居照旧只按读数算 --

    @Test
    @DisplayName("非线邻居 (世界里的中继器) 只被读、不被走 ⇒ 它答 15 就照旧算输入 (不误伤)")
    void aNeighbourThatIsNotALineIsAnsweredByItsReadingAlone() {
        FakeInnerLine line = new FakeInnerLine().line(DUST_HOST).fedBy(DUST_HOST);

        assertFalse(line.wire(REPEATER), "中继器不是线: 这一条判据不认它");
        boolean ours = SyncGroups.fedByGroupOnly(line, DUST_HOST, SyncGroups.MAX_WIRE_CELLS);

        assertFalse(ours, "线旁边那一个中继器答了 15: 外面有人喂, 这份读数照旧是输入");
        assertFalse(line.walked.contains(REPEATER), "它不该被当成线走掉 - 走线只走线");
        assertFalse(SyncGroups.fedByGroupOnly(line, REPEATER, SyncGroups.MAX_WIRE_CELLS),
                "读到的那一格本身不是线时, 判据一句话都不说: 读数原样进掩码 (与上一版逐字相同)");
        assertEquals(15, SyncGroups.credited(15, false));
    }

    // ------------------------------------- 3. 源码接线: 只在那一趟走线里, 一份判据 --

    @Test
    @DisplayName("走方块内线的那几句都在 WireWalk 里, 而且用的还是那两份存储入口与那一份名单")
    void theInnerLineIsWalkedInsideTheSameWalk() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String walk = bodyOf(groups, "private static final class WireWalk ");

        assertTrue(walk.contains("private boolean innerWire(BlockPos pos)"),
                "\"这一格是不是方块里的一根线\"只有这一个入口");
        assertTrue(walk.contains("this.store.slotAt(pos)"),
                "世界段问世界 store - 与它读到的那一份读数同一格, 不是第二份表");
        assertTrue(walk.contains("CarrierStore.carriedSlotAt(this.level, this.carrier, pos)"),
                "搬运段问<b>同一台载体</b>的那一格 (新增的公开只读入口), 不是 level 里碰巧同坐标的方块");
        assertTrue(walk.contains("CarrierStore.projectedHostAt(this.level, pos)"),
                "画出来那一格是投影: 世界 store 里没有它时问投影表 - 少了这一支, 被搬着的机器里那根粉在 drawn 那一半"
                        + "仍然是一个答 15 的方块 (那一半)");
        assertTrue(walk.contains("projected.slot()"), "投影表也只交出那一格里的元件, 与 mixin 回答这一格时同一份入口");
        assertTrue(walk.contains("slot.type.isWire()"),
                "线 = 元件类型为 DUST/SUPERCONDUCTOR (这一句在 ComponentType#isWire 里只写一次)");
        assertTrue(walk.contains("Direction.values()"),
                "方块里的线按六面连 (元件自己的连接判据), 不是原版粉那套台阶上下");
        assertTrue(walk.contains("state.getSignal(this.view, neighbour, side)"),
                "而它的邻居照旧用这一份方块视图问读数: 两种形态是同一个问题");
        assertEquals(4, occurrences(walk, "innerWire("), "一处声明 + 三处调用 (是线/连哪几格/旁边的源)");
        assertEquals(2, occurrences(walk, "isGroupMember("),
                "一处声明 + 一处调用: 方块里那一格问的还是同一份名单 - 抄第二份比对就会变成 4");

        String askAt = bodyOf(groups, "private static int askAt(");
        String askSide = bodyOf(groups, "private static int askSide(");
        assertFalse(askAt.contains("innerWire(") || askSide.contains("innerWire("),
                "六面那一层一个字都不改: 方块内那一格只在走线里认");
        assertEquals(2, occurrences(groups, "fedByGroupOnly("), "判据仍然只有一处定义 + 一处调用");
    }

    // ------------------------------------------------------------- 假的那根线 --

    /** 一条假的线, <b>方块里那一形态</b>: 哪几格是宿主里的线 (六面相连), 哪几格旁边有本组之外的源。 */
    private static final class FakeInnerLine implements SyncGroups.WireLine {

        private final Set<BlockPos> cells = new LinkedHashSet<>();

        /** 有外部来源的那几格 - 判据只问"有没有", 不问是谁。名字故意不与接口那个同名: 单参调用会判给后者。 */
        private final Set<BlockPos> fed = new LinkedHashSet<>();

        /** 真被走到的格 (判据问过"旁边有没有源"的那几格); "非线邻居没有被走"由此断言。 */
        private final Set<BlockPos> walked = new LinkedHashSet<>();

        FakeInnerLine line(BlockPos... positions) {
            for (BlockPos position : positions) {
                this.cells.add(position);
            }
            return this;
        }

        FakeInnerLine fedBy(BlockPos... positions) {
            for (BlockPos position : positions) {
                this.fed.add(position);
            }
            return this;
        }

        @Override
        public boolean wire(BlockPos pos) {
            return this.cells.contains(pos);
        }

        @Override
        public List<BlockPos> joined(BlockPos pos) {
            List<BlockPos> joined = new ArrayList<>();
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = pos.relative(direction);
                if (this.cells.contains(neighbour)) {
                    joined.add(neighbour);
                }
            }
            return joined;
        }

        @Override
        public boolean fedFromOutside(BlockPos pos) {
            this.walked.add(pos);
            return this.fed.contains(pos);
        }
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
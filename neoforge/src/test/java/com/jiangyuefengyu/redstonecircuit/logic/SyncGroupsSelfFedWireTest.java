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
 * <b>我们自己喂亮的那根粉绕回来贴到另一面时, 那一面不算输入。</b>
 *
 * <p>那条自锁 ({@code side=west drawn=…=15 structure=…=15 answer=15 power=15 written=false}) 与
 * {@code skipped=same-group} 一条都不命中: 那根粉不是成员, 它答的 15 是我们自己从<b>另一个面</b>打进去、绕回来的回声。
 *
 * <p>判据 ({@link SyncGroups#fedByGroupOnly}) 的一句话: <b>一根线里的电只有两个来源 - 它旁边本组之外的某个真源, 或者
 * 本组自己往它里面打的 15。</b>整根线走完都找不到前者, 这一面读到的就只可能是后者。于是这里钉两头:
 *
 * <ul>
 *   <li><b>环</b>: 火把 + 一根粉同时贴着输出面与候选输入面, 线里一个外部来源都没有 ⇒ 那一面不算输入 (掩码不置位);</li>
 *   <li><b>真源</b>: 同一根线, 线上有一个外部来源 (拉杆/红石块/另一支火把) ⇒ 仍然算输入, 整组照样能被点亮。</li>
 * </ul>
 *
 * <p>纯值那几条不需要方块: 走线要问的三件事由 {@link FakeLine} 交进去, 与真正那一份
 * ({@code SyncGroups.WireWalk}) 是同一个接口 - 于是判据本身在没有方块注册表的地方也能钉住。
 */
class SyncGroupsSelfFedWireTest {

    /** 我们正在往这一面输出 - 粉贴在这里, 于是它答的 15 从这一格出发。 */
    private static final BlockPos DRIVEN = new BlockPos(0, 0, -1);

    /** 绕回来的那一格: 它贴在火把的西面, 也就是"候选输入面"。 */
    private static final BlockPos COMES_BACK = new BlockPos(-1, 0, 0);

    // ------------------------------------------------ 1. 环: 那一面不算输入 --

    @Test
    @DisplayName("粉同时贴着输出面与候选输入面, 线里没有外部来源 ⇒ 西面读到的那份 15 是回声, 不算输入")
    void aLoopOfOurOwnMakingIsNotAnInput() {
        // vanilla 的成线形状: 一格一格往西绕, 于是西面那一格与输出面那一格是同一根粉 (四步之后才连得上,
        // 普通粉每步掉一级 - 而这正是"答 15"只可能是一根超导粉或一份满强度读数的理由, 见报告)。
        FakeLine wire = new FakeLine()
                .line(DRIVEN, new BlockPos(0, 0, -2), new BlockPos(-1, 0, -2), new BlockPos(-1, 0, -1), COMES_BACK);

        boolean ours = SyncGroups.fedByGroupOnly(wire, COMES_BACK, SyncGroups.MAX_WIRE_CELLS);

        assertTrue(ours, "整根线里没有本组之外的任何真源: 这份电只可能是我们自己打进去的");
        assertEquals(0, SyncGroups.credited(15, ours),
                "于是西面这一份读数进不了掩码 - OR 不再恒成立, 自锁在这一面断开");
    }

    // ------------------------------------- 2. 真源: 同一根线, 外面有人喂 --

    @Test
    @DisplayName("同一根粉由外部点亮 (线上有一支拉杆/红石块) ⇒ 仍然算输入, 整组照样亮")
    void aWireLitFromOutsideIsStillAnInput() {
        FakeLine wire = new FakeLine()
                .line(DRIVEN, new BlockPos(0, 0, -2), new BlockPos(-1, 0, -2), new BlockPos(-1, 0, -1), COMES_BACK)
                .fedBy(new BlockPos(-1, 0, -2));

        boolean ours = SyncGroups.fedByGroupOnly(wire, COMES_BACK, SyncGroups.MAX_WIRE_CELLS);

        assertFalse(ours, "线上有一个本组之外的来源: 这一份电不归我们解释, 环的判据不许把它吞掉");
        assertEquals(15, SyncGroups.credited(15, ours), "于是它照旧进掩码 - 外面的电仍然点亮整组");
    }

    @Test
    @DisplayName("线本身不是线 (导体/纯源/中继器) 时判据一句话都不说")
    void anythingThatIsNotAWireIsAnsweredByItsReadingAlone() {
        FakeLine empty = new FakeLine();
        assertFalse(SyncGroups.fedByGroupOnly(empty, COMES_BACK, SyncGroups.MAX_WIRE_CELLS),
                "读到的不是线: 没有'我们喂它'这回事, 9 与 15 都照旧按读数算");

        FakeLine single = new FakeLine().line(COMES_BACK);
        assertTrue(SyncGroups.fedByGroupOnly(single, COMES_BACK, SyncGroups.MAX_WIRE_CELLS),
                "一根孤零零的线同样只可能是我们喂的 (现实里它读到的就是 0, 于是这一条不改变任何行为)");
    }

    @Test
    @DisplayName("线比走线上限还长 ⇒ 说不清 ⇒ 当外部 (宁可留一个真输入, 也不许吞掉真的)")
    void aWireTooLongToWalkKeepsTheInput() {
        FakeLine longWire = new FakeLine();
        BlockPos[] cells = new BlockPos[SyncGroups.MAX_WIRE_CELLS + 2];
        for (int step = 0; step < cells.length; step++) {
            cells[step] = new BlockPos(-1 - step, 0, 0);
        }
        longWire.line(cells);

        assertFalse(SyncGroups.fedByGroupOnly(longWire, cells[0], SyncGroups.MAX_WIRE_CELLS),
                "走到上限还没找到来源, 不等于'找到了没有': 这一支答 false (算输入)");
    }

    // ------------------------------------------------- 3. 源码接线 (只有一处) --

    @Test
    @DisplayName("判据只有一处定义一次调用, 而且只在非吸附面那一支上生效")
    void theCriterionSitsOnceAndOnlyOnTheFiveFaces() throws IOException {
        String groups = main("logic", "SyncGroups.java");

        assertTrue(groups.contains("static boolean fedByGroupOnly(WireLine line, BlockPos readAt, int maxCells)"),
                "判据的签名就是这三件事: 一条线、读到的这一格、走线上限");
        assertEquals(2, occurrences(groups, "fedByGroupOnly("),
                "一处定义 + 一处调用: 六面里各写一遍就是六份迟早不一致的真值");

        String askAt = bodyOf(groups, "private static int askAt(");
        String askSide = bodyOf(groups, "private static int askSide(");

        assertEquals(1, occurrences(askAt, "askSide("), "五面整支只有一个入口");
        assertTrue(askAt.indexOf("if (side == support) {") < askAt.indexOf("askSide("),
                "而且排在吸附面那一支之后: 它只走 else 那一半");
        assertFalse(askAt.contains("withoutOurOwnLoop("), "判据不在六面循环里散开");
        assertEquals(2, occurrences(askSide, "withoutOurOwnLoop("),
                "两份存储各读数一次, 于是各判一次 (画出来的那一格在 level 里, 机器那一格在机器里)");
        assertTrue(askSide.indexOf("Math.max(drawn, structure)") > askSide.lastIndexOf("withoutOurOwnLoop("),
                "每一份在取大之前就判完: 取大之后再判就分不清是哪一份的电");
        assertEquals(3, occurrences(groups, "withoutOurOwnLoop("), "一处定义 + 两份读数各一次调用");
    }

    @Test
    @DisplayName("走线用本组那一条判据认人, 而真源用与六面同样的问法读")
    void theWalkUsesTheSameGroupAndTheSameSignalQuestion() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String walk = bodyOf(groups, "private static final class WireWalk ");

        assertTrue(walk.contains("this.group.holdsWorld(pos)") && walk.contains("this.group.holdsInside(this.carrier, pos)"),
                "本组成员按同一份名单比 (世界坐标 / 同一台载体的 local), 与 sameGroupNeighbour 同一条规矩");
        assertTrue(walk.contains("pos.equals(this.self)"),
                "还有自己那一格: 一个自成一组的成员在名单里没有别人, 它自己的 15 更不能给自己作证");
        assertTrue(walk.contains("SyncTorchRule.ask("),
                "外部来源的读法与六面同一条 (导体读弱信号, 其余连 direct 一起读)");
        assertTrue(walk.contains("state.getSignal(this.view, neighbour, side)"),
                "而且问的是<b>这一份存储</b>里那一格, 不混另一份");
        String criterion = bodyOf(groups, "static boolean fedByGroupOnly(");
        assertFalse(criterion.contains("getBlockState") || criterion.contains("this.view"),
                "判据自己不读世界: 走线那三件事是交进来的, 于是它能脱层测");
    }

    // ------------------------------------------------------------- 假的那根线 --

    /** 一条假的线: 哪几格是线 (四向同层成线), 以及哪几格旁边有本组之外的源。 */
    private static final class FakeLine implements SyncGroups.WireLine {

        private final Set<BlockPos> cells = new LinkedHashSet<>();

        /** 有外部来源的那几格 - 判据只问"有没有", 不问是谁。 */
        private final Set<BlockPos> fed = new LinkedHashSet<>();

        FakeLine line(BlockPos... positions) {
            for (BlockPos position : positions) {
                this.cells.add(position);
            }
            return this;
        }

        /** 记下"这几格旁边有本组之外的源"。名字故意与接口那个 {@code fedFromOutside(BlockPos)} 不同: 单参调用会判给后者。 */
        FakeLine fedBy(BlockPos... positions) {
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
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = pos.relative(direction);
                if (this.cells.contains(neighbour)) {
                    joined.add(neighbour);
                }
            }
            return joined;
        }

        @Override
        public boolean fedFromOutside(BlockPos pos) {
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

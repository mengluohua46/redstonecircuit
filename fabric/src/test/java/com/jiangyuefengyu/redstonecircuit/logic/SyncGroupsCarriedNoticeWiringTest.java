package com.jiangyuefengyu.redstonecircuit.logic;

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
 * <b>被搬着的两种形态写回之后, 都通知机器里自己那一格 - 一次, 而且只在答案或掩码真的变了时。</b>
 *
 * <p>被搬着的<b>内嵌形态</b>写回一句通知都没有 ({@code writeCarried} 只改 {@code slot.power}),
 * 而 {@code pokeDrawn} 只叫醒"画出来那一格"。于是结构上那根红石粉被激活之后再撤不掉: 火把读到它 → 整组亮 → 不再往
 * 那一面输出 → <b>没有人告诉那根粉再算一次</b> → 粉保留上一次的 15 → 火把每趟把它读成输入 → 自锁。
 *
 * <p>这一条链在跑到之前什么都不报 (静默自锁, 不崩、不写日志), 所以按源码位置钉: 通知走
 * {@code CarrierStore#notifyCellChanged}, 判据 (叫得到哪一格) 只在载体层 {@code storageInLevel} 那一句上, 组 pass 里
 * 只有一句"这一格变了"; 而世界形态那条 {@code applyLit} 一个字未改。
 */
class SyncGroupsCarriedNoticeWiringTest {

    @Test
    @DisplayName("内嵌形态: 答案真的落地之后, 通知机器自己那一格, 一次")
    void theEmbeddedWriteNotifiesTheMachinesOwnCellOnce() throws IOException {
        String writeCarried = bodyOf(main("logic", "SyncGroups.java"), "private static boolean writeCarried(");

        assertEquals(1, occurrences(writeCarried, "CarrierStore.notifyCellChanged("),
                "一次真实变化 = 一次通知: 不是每 tick 一次, 也不是每面一次");
        assertTrue(writeCarried.contains(
                        "CarrierStore.notifyCellChanged(level, member.spot().carrier(), member.spot().pos())"),
                "叫的是机器里那一格 (spot 的 local), 不是画出来那一格 - 画出来那一格由世界那一侧管");
        assertEquals(0, occurrences(writeCarried, "pokeDrawn("),
                "这一支要补的正是'机器里那一格': 上一版这里一句通知都没有");
    }

    @Test
    @DisplayName("内嵌形态: 通知在两个早退之后, 而且只在回写真的落地时")
    void theEmbeddedNoticeRidesOnARealChangeThatLanded() throws IOException {
        String writeCarried = bodyOf(main("logic", "SyncGroups.java"), "private static boolean writeCarried(");

        int answerChanged = writeCarried.indexOf("boolean powerChanged = slot.power != answer.power();");
        int maskChanged = writeCarried.indexOf("boolean maskChanged = slot.syncInputs != answer.inputs();");
        int earlyOut = writeCarried.indexOf("if (!powerChanged && !maskChanged)");
        int landed = writeCarried.indexOf("boolean written = CarrierStore.writeBackCarriedSlot(");
        int notify = writeCarried.indexOf("CarrierStore.notifyCellChanged(");

        assertTrue(answerChanged >= 0 && maskChanged > answerChanged,
                "答案与掩码各自比一次: 两个都没变就没有通知");
        assertTrue(earlyOut > maskChanged, "没变就早退, 早退在通知之前");
        assertTrue(landed > earlyOut, "回写只看索引里那条记录还在不在");
        assertTrue(notify > landed, "只在落地之后才叫: 没落地就没有人该听见");
        assertTrue(writeCarried.contains("if (written) {"),
                "回写落空 (这趟收到答案之前它被放下了) 时一个邻居都不叫");
    }

    @Test
    @DisplayName("被搬着、没有槽位的那一支: 只有掩码变了也要叫, 世界形态那条一个字未改")
    void theStandingCarriedCellIsNotifiedWhenOnlyTheMaskMoved() throws IOException {
        String write = bodyOf(main("logic", "SyncGroups.java"),
                "private static boolean write(ServerLevel level, InnerRedstoneStore store,");

        assertEquals(1, occurrences(write, "CarrierStore.notifyCellChanged("), "写回里只有这一处通知, 一次");
        assertTrue(write.contains("if (changed || cell.inputs() != answer.inputs()) {"),
                "写方块那一半只在状态变了时通知邻居, 于是'只有掩码变了'一直是空白: 输出面变了, 没人重新问");
        assertTrue(write.contains("CarrierStore.notifyCellChanged(level, member.spot().carrier(), pos)"),
                "叫的是载体里那一格 (pos), 不是画出来那一格");

        assertTrue(write.contains("boolean litChanged = SyncTorchBlock.applyLit(level, pos, answer.power() > 0);"),
                "世界形态那条仍是 applyLit 的老路");
        assertTrue(write.contains("InnerRedstoneNetwork.notifyOutputChanged(level, pos);"),
                "世界里的内嵌形态仍是它原来那一句 - 一个字没改");
    }

    @Test
    @DisplayName("世界形态的通知一个字没改: applyLit 仍然自己 updateNeighborsAt")
    void theWorldPathStillNotifiesItself() throws IOException {
        String block = main("block", "SyncTorchBlock.java");

        String applyLit = bodyOf(block, "private static boolean applyLit(");
        assertTrue(applyLit.contains("level.updateNeighborsAt(pos, state.getBlock());"),
                "世界形态那条已经通知了: 这一次改动不碰它");
        assertFalse(applyLit.contains("CarrierStore"), "也不许把它改道到载体层: 世界坐标与载体无关");

        String refresh = bodyOf(block, "public static void refreshCarried(");
        assertTrue(refresh.contains("level.updateNeighborsAt(drawn"),
                "refreshCarried 仍是画出来那一格: 世界那一侧听的是它");
    }

    @Test
    @DisplayName("判据只在载体层那一句上: 叫得到哪一格由 storageInLevel 答, 组 pass 里没有第二句话")
    void theCriterionIsTheSingleOneInTheCarrierLayer() throws IOException {
        String store = main("carrier", "CarrierStore.java");

        String entry = bodyOf(store, "public static boolean notifyCellChanged(");
        assertTrue(entry.contains("!isActive() || level == null"),
                "没装载体 / 没有 level 时一次静态布尔读就答 false: 无载体时零变化");
        assertTrue(entry.contains("InnerRedstoneNetwork.notifyOutputChanged(level, pos)"),
                "走的是求解器世界那一趟听的通知管线: 一轮 solve 里会被合并成一次, 不在其中时立即发");

        String decide = bodyOf(store, "static BlockPos cellNoticeInLevel(");
        assertTrue(decide.contains("!storageInLevel(carrier)"),
                "判据还是那一句 - 与读一格、写一格、写掩码同一条");
        assertFalse(decide.contains("isContraption"), "第二条判据就是第二个答案");

        String groups = main("logic", "SyncGroups.java");
        assertFalse(groups.contains("isSubLevel"), "组 pass 不许自己判'这台载体是哪种'");
        assertFalse(groups.contains("isContraption"), "同上: 判据散在调用点迟早给出两个答案");
    }

    // ------------------------------------------------------------- helpers --

    private static int occurrences(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
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
}

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
 * <b>组 pass 的搬运分支真的经过"载体块读写"那一对入口, 而且两种载体之间只有一处判据.</b>
 *
 * <p>这一条链在跑到之前什么都不报: 直接读 {@code level} 的版本对 Sable 完全正确, 只有 Create contraption 上那支
 * 悄悄不亮 - 不崩、不写日志、单元测试全绿。所以这里按源码位置钉: 组 pass 里 {@code carriage} 那一支读走
 * {@code CarrierStore#carriedCellAt}, 写走 {@code CarrierStore#setCarriedCell}, 而"Sable / Create 分岔在哪"这个问题
 * 在组 pass 里<b>一个答案都没有</b> - 它只在载体层那一条判据上。
 */
class SyncGroupsCarriedCellWiringTest {

    @Test
    @DisplayName("memberAt 判'这一格是不是同步火把'走载体入口, 不再问 level 的方块网格")
    void theCarriedMemberIsReadThroughTheCarrierEntry() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String memberAt = bodyOf(groups, "private static Member memberAt(");

        assertTrue(memberAt.contains("CarrierStore.carriedCellAt(level, spot.carrier(), spot.pos())"),
                "被搬着、没有槽位的那一支, 那一格问的是载体层: 一台 contraption 的 local 是相对锚点的坐标, "
                        + "拿它去问 level 会问到别人的一格, 于是那支被判成'不是火把'");
        assertFalse(memberAt.contains("BlockState carried = level.getBlockState("),
                "上一版正是这一句: 它对 Sable 成立, 对 Create contraption 永远答 air");
        assertTrue(memberAt.contains("carried.getBlock() instanceof SyncTorchBlock"),
                "而'是不是同步火把'仍然由这一格的方块说 - 只是那一格从哪读变了");
    }

    @Test
    @DisplayName("驱动集与写回都带着那一格的读数 (LIT + 掩码不是两次读), 而六面读数那一层不再碰它")
    void theReadingRidesWithTheMember() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String askAt = bodyOf(groups, "private static int askAt(");

        // 这两件事读的是同一格, 而它们只在 driveAt 这一处读: 抓 tick 开头那一份时读一次, askAt 抓"此刻"那一份时
        // 再读一次, 都是"一个成员一次" (次数由 SyncGroupsDriveTest 钉住)。六面循环里要是每面读一遍, 依据就会跟着
        // 轮次之间的写回走 - 那是自锁的入口。
        String driveAt = bodyOf(groups, "private static Drive driveAt(");
        assertTrue(driveAt.contains("member.cell()"),
                "上一 tick 的掩码与那一刻的 LIT 都来自 memberAt 读到的那一格: 这一趟不再问第二遍");
        assertTrue(driveAt.contains("cell.inputs()"), "掩码就是那一格自己的读数, 落在哪一份由载体层决定");
        assertTrue(driveAt.contains("cell.state()"), "亮没亮就是那一格方块状态里的 LIT");
        assertEquals(0, occurrences(askAt, "member.cell()"),
                "六面读数那一层不直接碰它: 三处读音都收在 driveAt 里, 一趟 tick 与每一轮各问它一次");

        String write = bodyOf(groups, "private static boolean write(ServerLevel level, InnerRedstoneStore store,");
        assertTrue(write.contains("CarrierStore.setCarriedCell("),
                "写回走那一对入口的另一半: 方块 (LIT) 与掩码一起写, 而写到哪一份存储不是组 pass 的事");
        assertTrue(write.contains("CarriedSyncTorches.noteAnswer("),
                "答案同时留在机器作用域的表里: 方块晚一步才被放回这一格时, 驱动那一趟仍写得上去");
    }

    @Test
    @DisplayName("Sable / Create 的分岔在载体层只有一处判据, 组 pass 里一处也没有")
    void thereIsExactlyOneCriterionAndItIsNotHere() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        assertFalse(groups.contains("isSubLevel"), "组 pass 不许自己判'这台载体是哪种': 判据在载体层, 而且是同一条");
        assertFalse(groups.contains("isContraption"), "同上 - 散在调用点的判据迟早会给出两个答案");
        assertFalse(groups.contains("getContraptionWorld"), "主源集也不许出现 Create 的类型与调用");

        String store = main("carrier", "CarrierStore.java");
        String criterion = bodyOf(store, "public static boolean storageInLevel(");
        assertTrue(criterion.contains("carrier.isSubLevel()"),
                "判据只有这一句: 一台子层级的方块就躺在自己的 plot 坐标上, contraption 的在它自己的 map 里");
        assertTrue(bodyOf(store, "public static CarriedCell carriedCellAt(").contains("storageInLevel(carrier)"),
                "读那一格按它分岔");
        assertTrue(bodyOf(store, "public static boolean setCarriedCell(").contains("storageInLevel(carrier)"),
                "写那一格与写掩码也按它分岔 - 三处同一个答案");
        assertFalse(store.contains("com.simibubi"), "载体层不许出现 Create 的类型: 那一半在 compat 源集里");

        String frame = main("carrier", "ProductFrame.java");
        assertTrue(bodyOf(frame, "public static ProductFrame forHost(").contains("CarrierStore.storageInLevel(carrier)"),
                "读数框也问同一条判据: 存储不在 level 的载体, 邻居在它自己的 world 里");
        assertTrue(bodyOf(frame, "public static CarriedSignals signalsIn(")
                        .contains("view.getBlockState(localNeighbour)"),
                "于是 contraption 的邻居问的是它自己那个 world, 不是 local 坐标上的某个人");
    }

    @Test
    @DisplayName("compat 那一半只写 contraption 自己的方块表, 绝不动 level")
    void theWriteHalfStaysInTheCompatSourceSet() throws IOException {
        String compat = compat("CarrierStructureAccess.java");
        String setBlock = bodyOf(compat, "boolean setBlock(CarrierId carrier, BlockPos local, BlockState state)");
        assertTrue(setBlock.contains("setInContraption("), "写那一半走它自己的方块表");
        assertFalse(setBlock.contains("level.setBlock("), "子层级那一支不在 compat 里: 它在载体层 (writeInPlot)");

        String write = bodyOf(compat, "private boolean setInContraption(");
        assertTrue(write.contains("contraption.getBlocks()"),
                "一台 contraption 的方块只存在于它自己的 map 里 (Create 的 getBlocks)");
        assertTrue(write.contains("entity.setBlock(local, new StructureBlockInfo(local, state, info.nbt()))"),
                "写一格走 Create 自己那一条'写进去 + 告诉客户端' (AbstractContraptionEntity#setBlock: 先 put, "
                        + "再发 ContraptionBlockChangedPacket), 于是客户端在飞行中也看得到亮灭, 而这里一个包类型都不出现");
        assertTrue(write.contains("info.nbt()"), "方块实体数据原样带过去: 改的是状态, 不是它是什么方块");
        assertFalse(write.contains("level.setBlock("), "锚点相对的坐标在 level 里是别人的一格, 一个字都不许写");
    }

    @Test
    @DisplayName("下发给客户端的那个包: 写在写回之后, 而且只有 contraption 这一种载体会走到它")
    void theClientPacketRidesOnTheContraptionWriteOnly() throws IOException {
        String compat = compat("CarrierStructureAccess.java");
        String setBlock = bodyOf(compat,
                "boolean setBlock(CarrierId carrier, BlockPos local, BlockState state)");
        int criterion = setBlock.indexOf("CarrierStore.storageInLevel(carrier)");
        int delegation = setBlock.indexOf("setInContraption(");
        assertTrue(criterion >= 0 && delegation > criterion,
                "判据在委托之前: 存储就是 level 的那一种 (Sable 子层级) 连 compat 这一半都进不去, "
                        + "于是那个包绝不会因为它们被发出去");

        String write = bodyOf(compat, "private boolean setInContraption(");
        int changed = write.indexOf("info.state().equals(state)");
        int publish = write.indexOf("entity.setBlock(");
        assertTrue(changed >= 0 && publish > changed,
                "发包在'这一格真的变了'之后: 同一个 LIT 被重新断言一次时一个字都不发, "
                        + "于是组 pass 每 tick 跑一遍也不会变成每帧发包");
        assertEquals(1, occurrences(write, "entity.setBlock("), "下发的唯一入口, 没有第二条路");
        assertEquals(0, occurrences(write, "blocks.put("),
                "也不再自己 put 之后再补一个包: 写了却不说 = 客户端一直是旧状态");
        assertEquals(0, occurrences(write, "Packet"),
                "包本身由 Create 的 setBlock 发: 这里既不认识包类型, 也没有'发在写之前'这一种时序");
        assertTrue(write.contains("return written != null && !written.state().equals(info.state());"),
                "'真的改了没有'问的是那张 map 本身, 不是假定");
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

    private static String compat(String name) throws IOException {
        return read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics", name);
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

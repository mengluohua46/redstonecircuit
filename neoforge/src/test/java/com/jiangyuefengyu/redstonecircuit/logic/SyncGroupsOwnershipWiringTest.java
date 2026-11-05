package com.jiangyuefengyu.redstonecircuit.logic;

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
 * <b>那道归属判据真的接在两条路上, 而且不是每帧现问。</b>
 *
 * <p>这些行只有在跑游戏时才会执行到, 所以它们从源码上读: {@code snapshotOf} 走的那条链, 组 pass 收表走的那条链, 判据
 * 问的是搬运层的哪一句, 以及位姿为什么不是每个成员各问一次。删掉其中任何一处, 那条"连到很远的地方"的线
 * 就会回来 - 而它不会崩, 只会画错。
 */
class SyncGroupsOwnershipWiringTest {

    @Test
    @DisplayName("快照走的那条链: snapshotOf → 收表 → 归属判据")
    void theSnapshotAsksWhoOwnsEachWorldPosition() throws IOException {
        String groups = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "SyncGroups.java");

        String snapshotOf = bodyOf(groups, "public static Snapshot snapshotOf(");
        assertTrue(snapshotOf.contains("roster(level,"),
                "快照的名单来自那次收表 - 于是世界段的每个位置都已经过判据, 而不是把 store 里的坐标原样发出去");
        assertTrue(snapshotOf.contains("new Inquiry(level)"),
                "并且带着一本一趟的账: 归属与位姿在这一份名单里只问一次");

        String levelRoster = bodyOf(groups, "private static Roster roster(ServerLevel level, SyncChannels channels");
        assertTrue(levelRoster.contains("roster(channels, inquiry::spotAt"),
                "世界那两种形态取名单时先问归属: 组 pass 与快照走的是同一个函数, 于是两条路不会有两个答案");

        String resolve = bodyOf(groups, "private Spot resolve(");
        assertTrue(resolve.contains("CarrierStore.ownedHostAtPlot("),
                "判据就是搬运层回答'这一格归谁'的那一句: 一台 Sable 子层级的方块站在自己的 plot 坐标上, 于是"
                        + "\"它在 level 里有一格\"不是归属的答案");
        assertTrue(resolve.contains("CarrierStore::structureIsAlive"),
                "问的是'归一台活着的机器': 死掉的子层级不许把坐标留在搬运段, 也不许把它换成搬运段");
        assertTrue(resolve.contains("CarrierProjection.drawnBlockOf("),
                "认出来之后还要一位姿: 说不出这一格画在哪, 就没有坐标可给客户端");
        assertTrue(resolve.contains("drawable ? owned.localPos() : null"),
                "而换算不出来时那一支两段都不放 - 宁可这一帧不画, 也不给一个很远的地方");
        assertTrue(resolve.contains("Spot.standing(pos)"),
                "不归任何机器时才照旧: 世界里的一格就是世界段的一格");
    }

    @Test
    @DisplayName("组 pass 的收表用同一道判据, 而且同一个成员不会两段各算一次")
    void theGroupPassUsesTheSameRuleOnce() throws IOException {
        String groups = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "SyncGroups.java");

        String levelRoster = bodyOf(groups, "private static Roster roster(ServerLevel level, InnerRedstoneStore store");
        assertTrue(levelRoster.contains("inquiry"),
                "收表拿着那一本账: 它问过的归属在这一次 pass 里不再问第二遍");
        assertTrue(levelRoster.contains("roster.holds(spot)"),
                "机器作用域那张表里的同一格, 已经进过名单就不再算第二个成员 - 否则同一支火把会在两段里各一次");

        assertTrue(bodyOf(groups, "public static void tick(").contains("new Inquiry(level)"),
                "一本账够一整个 tick 用完: 每一轮 pass 都重新问一遍归属是白花");

        String inquiry = bodyOf(groups, "private static final class Inquiry {");
        assertTrue(inquiry.contains("Map<CarrierId, CarrierPose> poses"),
                "位姿按载体记: 一台 Sable 子层级问一次位姿要去容器里查一次, 而这一趟会对同一台机器问好几次");
        assertTrue(inquiry.contains("this.poses.containsKey(carrier)"),
                "记的是'问过了'而不是'问到了': 一台读不到位姿的机器同样不该被反复问");
        assertTrue(inquiry.contains("CarrierStore.poseOf(this.level, carrier)"),
                "位姿只有一个来源, 与搬运求解同一句");
        assertTrue(inquiry.contains("Map<BlockPos, Spot> segments"),
                "归属按位置记: 同一个坐标一趟只问一次");
    }

    @Test
    @DisplayName("客户端那一层兜底: 只在世界↔世界那条支路上, 读不到且远才跳过")
    void theClientFallbackSitsOnTheWorldPairBranch() throws IOException {
        String lines = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "SyncGroupLines.java");

        String gate = bodyOf(lines, "public record WorldGate(");
        assertTrue(gate.contains("FAR_BLOCKS * FAR_BLOCKS"),
                "门槛是一个明确的半径, 不是'看起来远'");
        assertTrue(gate.contains("!this.loaded.test("),
                "而且必须同时'读不到': 玩家站在自己那一组旁边时区块正在加载, 这道判据一格都不砍");

        String links = bodyOf(lines,
                "public static List<Link> links(int channel, Collection<Spot> members, WorldGate gate)");
        int worldPair = links.indexOf("if (!together) {");
        assertTrue(worldPair > 0, "那条支路还在: 两端不在同一台机器里, 用的是各自的世界坐标");
        assertFalse(links.substring(0, worldPair).contains("gate.skips("),
                "同一台机器内部的那一条走位姿栈, 一台机器说了算, 那里没有'很远'这回事");
        assertTrue(links.substring(worldPair).contains("gate.skips(from) || gate.skips(to)"),
                "兜底只在这一条支路上, 而且两端都问: 画错的可能是任何一端");

        String draw = bodyOf(lines, "static void draw(");
        assertTrue(draw.contains("gateOf(level)"),
                "判据一帧一份, 由画线的这一层建出来");
        assertTrue(draw.contains("level::hasChunkAt"), "读不读得到问的是世界自己");
        assertTrue(draw.contains("gate);"), "并且真的递给了配对那一步 - 建了不用等于没有");

        String gateOf = bodyOf(lines, "private static WorldGate gateOf(");
        assertTrue(gateOf.contains("Minecraft.getInstance().player"),
                "玩家在哪: 那是'离玩家多远'唯一的意义");
        assertTrue(gateOf.contains("WorldGate.OPEN"),
                "没有玩家可问的那一帧 (菜单/加载中) 不砍任何线");
    }

    // ------------------------------------------------------------- helpers --

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

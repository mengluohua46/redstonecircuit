package com.jiangyuefengyu.redstonecircuit.carrier;

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
 * <b>被一台机器搬走的那一支同步火把: 三处接线, 一个都不能少.</b>
 *
 * <p>这条链上的每一段都在别处跑: 候选集在 compat 的认领里、答案在组 pass 里、落地在搬运层的释放里。拆掉任何一段代码都
 * 编译得过, 也不会让别的用例变红 —— 只会让"频道跟着走"在游戏里悄悄变成"那一支落地后自己一个人"。所以这里按源码位置钉。
 */
class CarriedSyncTorchWiringTest {

    @Test
    @DisplayName("认领的候选里有立在世界里的同步火把 (store 的另一张表), 认领时它的号码进机器作用域")
    void theClaimConsidersStandingSyncTorches() throws IOException {
        String claim = compat("SableCarrierClaim.java");
        String candidates = bodyOf(claim, "private static List<BlockPos> candidates(");
        assertTrue(candidates.contains("store.syncTorchPositions()"),
                "候选集必须问 store 的 syncTorch 表: 立着的那一支一个槽位都没有, 只读节点表永远看不到它");
        assertTrue(candidates.contains("CarriedSyncTorches.pending(level)"),
                "以及那些刚刚离开世界的: 它们那一行随方块钩子删掉了, 号码在载体层等着被认领");

        String body = bodyOf(claim, "private static boolean claimSyncTorch(");
        assertTrue(body.contains("CarriedSyncTorches.capture(space.carrier(), local, origin,"),
                "认领把号码与这一刻的掩码交给 (机器, 机器内坐标) 那张表 - 这是「频道跟着走」唯一的一份数据");
        assertTrue(body.contains("store.removeSyncTorch(origin)"),
                "世界那一行必须收起来: 它按世界坐标存, 留着会被下一个站到那个坐标上的火把读到");
        assertTrue(body.contains("store.removeSyncTorch(local)"),
                "机器里那一格自己写下的那一行也要收: 否则组 pass 会把一支火把看成两个成员");
        assertTrue(body.contains("store.putSyncTorchInputs(local, inputs)"),
                "这一刻的读数交给机器里那一格, 于是机器上的邻居同 tick 看到的就是它立着时的那几面");

        int claimCall = bodyOf(claim, "static int claimFrom(").indexOf("claimSyncTorch(level, space, store,");
        int hostClaim = bodyOf(claim, "static int claimFrom(").indexOf("CarrierStore.startMoving(");
        assertTrue(claimCall > 0 && hostClaim > claimCall,
                "一支同步火把不是宿主: 它先被判出来, 于是它不会被当成一个带槽位的宿主记进搬运索引");
    }

    @Test
    @DisplayName("驱动那一趟会调 SyncTorchBlock.refreshCarried, 而且是在叫醒世界之前")
    void theDriverRefreshesTheCarriedTorch() throws IOException {
        String driver = compat("SableProductDriver.java");
        String walk = bodyOf(driver, "private static void tellDrawnSources(");
        int refresh = walk.indexOf("SyncTorchBlock.refreshCarried(level, cell, state)");
        int poke = walk.indexOf("toldFor(uuid).spots.add(drawn.asLong())");
        assertTrue(refresh > 0,
                "机器里那一格的 tick 永远不会来: 让答案落地的就是这一趟走遍每个格的遍历");
        assertTrue(poke > refresh, "而且在叫醒世界之前, 于是世界被叫醒时读到的已经是这一 tick 的答案");

        String torch = main("block", "SyncTorchBlock.java");
        String carried = bodyOf(torch, "public static void refreshCarried(");
        assertTrue(carried.contains("CarriedSyncTorches.at(localPos)"),
                "它写的是机器作用域那张表里定下的答案, 不是自己再算一遍 - 全组 OR 只有组 pass 那一处");
        assertFalse(carried.contains("SyncTorchRule.output("),
                "这里不许出现第二处算答案的地方");
        assertTrue(carried.contains("CarrierProjection.drawnBlockOf("),
                "亮灭一变, 世界要在它「看起来在哪」那一格听到: 机器里那一格的邻居属于 plot");
    }

    @Test
    @DisplayName("释放真的把频道行写回新位置 - 而且不依赖搬运索引里有没有东西")
    void theReleasePutsTheRowBackAtTheLandingPosition() throws IOException {
        String store = main("carrier", "CarrierStore.java");
        assertTrue(bodyOf(store, "public static int releaseAll(ServerLevel level, CarrierId carrier, "
                        + "@Nullable String reason) {")
                        .contains("return releaseAll(level, carrier, reason, null);"),
                "both overloads funnel into the one that lands them: a second release path is how one of them stops "
                        + "handing torches back");
        String release = bodyOf(store, "@Nullable CarriedHostIndex.Landing landing) {");
        assertTrue(release.contains("CarriedSyncTorches.handBack(level, carrier,"),
                "落地是这一支重新进入世界 store 的唯一时刻: 频道跟着走要在这一句里发生");
        int handBack = release.indexOf("CarriedSyncTorches.handBack(");
        int earlyReturn = release.indexOf("CarriedHostIndex index = indexOf(level);");
        assertTrue(earlyReturn > handBack,
                "而且排在「索引里什么都没有」那一句之前: 一台只带着一支火把的机器在索引里一条记录都没有");
        assertTrue(bodyOf(store, "public static void clear(ServerLevel level) {").contains("CarriedSyncTorches.clear()"),
                "一张按机器记的表不许活过它描述的那个 level");

        String table = main("carrier", "CarriedSyncTorches.java");
        String handBackBody = bodyOf(table, "public static int handBack(");
        assertTrue(handBackBody.contains("store.putSyncTorch(at, row.channel())"),
                "写回的是 putSyncTorch: 从这一刻起它又是世界 store 里的一行, 照旧随存档走");
        assertTrue(handBackBody.contains("store.putSyncTorchInputs(at, row.inputs())"),
                "这一刻的读数也跟着回来, 否则落地后第一趟 pass 会先灭一下");
        String plan = bodyOf(table, "public static Map<BlockPos, Row> landingPlan(");
        assertTrue(plan.contains("landing.landingFor(entry.getKey())") && plan.contains("entry.getValue().origin()"),
                "落点: 有位姿时用投影, 没有位姿时落回它离开世界的那一格");
    }

    @Test
    @DisplayName("组 pass 收进被搬着的那一支, 而且只在一处写答案")
    void theGroupPassOwnsTheCarriedStandingTorch() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        assertTrue(bodyOf(groups, "private static Roster roster(ServerLevel level, InnerRedstoneStore store,")
                        .contains("CarriedSyncTorches.rows()"),
                "被搬着的世界形态成员从机器作用域那张表收进来, 与内嵌形态、世界形态进同一张频道表");
        assertTrue(bodyOf(groups, "private static boolean carryingTorches(")
                        .contains("CarrierStore.isActive() && CarriedSyncTorches.any()"),
                "早退条件要问这一句: 机器里只有一支火把时世界 store 与搬运索引都可能是空的");
        assertTrue(bodyOf(groups, "private static boolean write(ServerLevel level, InnerRedstoneStore store,")
                        .contains("CarriedSyncTorches.noteAnswer("),
                "答案同时留在表里, 于是方块晚一步被放回那一格时仍写得上去");
        String frame = bodyOf(groups, "ProductFrame frameOf(CarrierId carrier) {");
        assertTrue(frame.contains("ProductFrame.forHost(carrier, poseOf(carrier))"),
                "六面读数走的就是它自己那台机器的位姿: 它的 local 坐标在世界里不是那一格");
        assertTrue(frame.contains("this.frames.get(carrier)"),
                "而框按载体记: 同一台机器这一趟只建一个, 位姿也只问一次");
    }

    @Test
    @DisplayName("没有物理后端时一个字节都不同: 每个新入口都在 CarrierStore.isActive() 之后")
    void nothingIsReachedWithoutABackend() throws IOException {
        String torch = main("block", "SyncTorchBlock.java");
        String onRemove = bodyOf(torch, "protected void onRemove(");
        int active = onRemove.indexOf("CarrierStore.isActive()");
        int stash = onRemove.indexOf("CarriedSyncTorches.stash(");
        int removeRow = onRemove.indexOf("store.removeSyncTorch(pos)");
        assertTrue(active > 0 && stash > active,
                "方块离开世界时先把号码交给载体层, 而这一句只在 Sable 那边活着时才存在");
        assertTrue(removeRow > stash,
                "顺序不能反: 先交出号码, 再删那一行 - 反过来号码就没了");

        String groups = main("logic", "SyncGroups.java");
        String roster = bodyOf(groups, "private static Roster roster(ServerLevel level, InnerRedstoneStore store,");
        assertTrue(roster.contains("if (CarrierStore.isActive()) {"),
                "连读那张表的资格都先问一句 isActive: 没有后端时这个类根本不会被加载");

        String store = main("carrier", "CarrierStore.java");
        assertTrue(bodyOf(store, "public static int releaseAll(ServerLevel level, CarrierId carrier, "
                + "@Nullable String reason,").contains("if (active) {"),
                "释放那一句同样在静态布尔之后");
    }

    @Test
    @DisplayName("客户端那一半不改: 搬运段还是 (机器, 机器内坐标) 两个分量")
    void thePayloadShapeIsUnchanged() throws IOException {
        String payload = main("network", "RCNetwork.java");
        assertTrue(payload.contains("public record CarriedMember(CarrierId carrier, BlockPos local)"),
                "被搬着的那一支进的是既有那一段, 于是客户端按位姿投影画线的代码一行都不用动");

        String hostSync = main("network", "HostSync.java");
        assertTrue(bodyOf(hostSync, "public static RCNetwork.SyncGroupPayload syncGroupSnapshotOf(")
                        .contains("SyncGroups.snapshotOf(level)"),
                "两段仍然出自同一次收集, 于是「世界那一段没有它、搬运那一段也没有它」这种快照造不出来");
    }

    // ------------------------------------------------------------- helpers --

    private static String main(String area, String name) throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", area, name);
    }

    private static String compat(String name) throws IOException {
        return read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                name);
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

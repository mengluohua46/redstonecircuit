package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>调频器改被搬着的那一支的频道: 这条链上的五处接线, 一处都不能少.</b>
 *
 * <p>每一段都能单独编译, 也可能各自跑得通: 解析、写入、回传、以及"世界侧不许先把这一下吞掉"。拆掉任何一段都不会让别的
 * 用例变红 - 只会让游戏里出现"机器里那支还是改不了频道"。所以按源码位置钉, 与 {@code CarriedSyncTorchWiringTest}
 * 同一个写法。
 */
class CarriedTunerWiringTest {

    @Test
    @DisplayName("点击先解析成搬运位, 再问世界: 被搬着的那一支的方块还躺在那一格, 顺序反了号码就写错地方")
    void theCarriedTargetIsResolvedBeforeTheWorldOne() throws IOException {
        String use = bodyOf(main("item", "FrequencyTunerItem.java"),
                "public static boolean use(ServerLevel level, ServerPlayer player,");
        int carried = use.indexOf("carriedTargetAt(level, pos)");
        int world = use.indexOf(": targetAt(level, pos)");
        assertTrue(carried > 0, "目标必须能是一台机器里的那一格");
        assertTrue(world > carried, "搬运位先问: 世界那一份在这个时候已经不是它的号码了");
        assertTrue(use.contains("SyncGroups.joinCarried(level, carried.carrier(), carried.local(), joined)"),
                "绑定走搬运位的入口, 而不是世界坐标那个 join");
        assertTrue(use.contains("SyncGroups.leaveCarried(level, carried.carrier(), carried.local())"),
                "解绑同理: 写的是机器里那一份");
        assertTrue(use.contains("SyncGroups.tick(level)"),
                "改完这一 tick 的答案要跟上: 组 pass 是幂等的, 照上一波的做法直接调");
    }

    @Test
    @DisplayName("不是火把就一个字节都不吞, 而准星判据也认搬运位 (否则 Shift+右键会变成清空手里)")
    void anOrdinaryTargetIsStillLeftAlone() throws IOException {
        String item = main("item", "FrequencyTunerItem.java");
        String use = bodyOf(item, "public static boolean use(ServerLevel level, ServerPlayer player,");
        assertTrue(use.contains("if (!target.present()) {")
                        && use.contains("return clear(level, player, hand);"),
                "目标不是同步火把时照旧只问 clear (潜行才清空手里的频道), 于是放置/开箱/扳手都照原样走");
        String look = bodyOf(item, "private static boolean lookingAtSyncTorch(");
        assertTrue(look.contains("carriedTargetAt(level, hit.getBlockPos())"),
                "准星指着一台机器里的一格也是「指着火把」: 少这一句, 那一下会被当成清空手里");
        assertTrue(bodyOf(item, "public static boolean clear(").contains("return false;"),
                "clear 的两条拒绝路径不变: 没有可清的东西不该吞掉点击");
    }

    @Test
    @DisplayName("解析用的是仓库里已有的两条路径, 没有第二套坐标换算")
    void theResolutionReusesTheExistingResolvers() throws IOException {
        String body = bodyOf(main("item", "FrequencyTunerItem.java"),
                "private static SyncGroups.CarriedTarget carriedTargetAt(");
        int active = body.indexOf("CarrierStore.isActive()");
        int hit = body.indexOf("CarrierStore.hitAt(level, pos)");
        int plot = body.indexOf("CarrierStore.ownedHostAtPlot(level, pos,");
        assertTrue(active > 0 && hit > active,
                "没有物理后端时一个字节都不动: 新的入口一律在 CarrierStore.isActive() 之后");
        assertTrue(hit > 0, "索引里有记录的那一支 (内嵌形态) 走搬运层自己的点击解析");
        assertTrue(plot > hit,
                "索引里没有记录的那一支 (被搬着的世界形态) 走所有权那一条: 它没有槽位, 认领时刻意不建记录");
        assertFalse(body.contains("localBlockOf(") || body.contains("transformPositionInverse("),
                "这里不许出现第二套坐标换算: 世界坐标 → (机器, 机器内坐标) 只有一个实现");

        String resolve = bodyOf(main("logic", "SyncGroups.java"),
                "public static CarriedTarget carriedTarget(ServerLevel level,");
        assertTrue(resolve.contains("CarrierStore.isActive()"), "读搬运层那两张表同样先问一句 isActive");
        assertTrue(resolve.contains("CarrierStore.indexOf(level)"),
                "内嵌形态的槽位从搬运索引取, 不是自己维护第二份");
        assertTrue(resolve.contains("CarriedSyncTorches.at(carrier, local)"),
                "世界形态那一行按 (机器, 机器内坐标) 整副地址问");
    }

    @Test
    @DisplayName("两种形态各写自己那一份存档: 内嵌写槽位并走既有回写通道, 世界形态写机器作用域表的那一行")
    void eachFormWritesItsOwnLedger() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String write = bodyOf(groups, "static boolean writeChannel(CarriedTarget target, int channel) {");
        assertTrue(write.contains("slot.syncGroup = wanted"), "内嵌形态: 号码写在槽位上, 与组 pass 写答案同一个字段");
        assertTrue(write.contains("CarriedSyncTorches.setChannel("), "世界形态: 号码写在机器作用域表的那一行上");
        assertFalse(write.contains("putSyncTorch"),
                "两条都不许写世界 store: 这一格在世界里的那一行已经随方块钩子收起来了");
        assertFalse(write.contains("HostSync"), "写入是纯值, 广播在调用方那一层");

        String join = bodyOf(groups, "public static void joinCarried(ServerLevel level,");
        assertTrue(join.contains("writeChannel(target, channel)"), "改哪一份只有一处决定");
        assertTrue(join.contains("announceCarried(level, target)"), "改了之后要让客户端听到");
        String announce = bodyOf(groups, "private static void announceCarried(");
        assertTrue(announce.contains("CarrierStore.writeBackCarriedSlot("),
                "内嵌形态走既有回传通道 (索引 + 存档变脏 + 一个搬运包)");
        assertTrue(announce.contains("HostSync.broadcastSyncGroups(level)"),
                "组的名单多了/少了一个成员: 客户端要的是整份名单");
        assertTrue(bodyOf(groups, "public static void leaveCarried(").contains("SyncTorchRule.UNBOUND"),
                "解绑 = 写一个非频道号, 与 join 的规矩同一条");

        String row = bodyOf(main("carrier", "CarriedSyncTorches.java"),
                "public static boolean setChannel(");
        assertTrue(row.contains("row.channel = channel"), "世界形态的号码是那一行自己的字段");
        assertNotNull(bodyOf(main("carrier", "CarriedSyncTorches.java"),
                "public static Row at(@Nullable CarrierId carrier,"), "按整副地址读那一行");
    }

    @Test
    @DisplayName("世界侧不再先把这一下吞掉: 机器里的坐标归搬运层所有, 而调频器要从那一层拿到点击")
    void theWorldSideNoLongerPreemptsTheTuner() throws IOException {
        String body = bodyOf(main("", "InnerRedstoneInteraction.java"),
                "private InteractionResult serverUseBlock(Player actor, Level world, InteractionHand hand,");
        int readsItem = body.indexOf("boolean tuner = stack.getItem() instanceof FrequencyTunerItem;");
        int gate = body.indexOf("tuner ? null : CarrierClickPriority.owner(serverLevel, pos)");
        assertTrue(readsItem > 0, "手里那个物品要先被认出来");
        assertTrue(gate > readsItem, "只在手里是调频器时才绕开那道闸门");
        assertTrue(body.contains("FrequencyTunerItem.use(serverLevel, player, hand, pos)"),
                "闸门之后仍然是同一个入口: 动作在物品自己那一层, 这里只决定 call 不 call");
        assertTrue(body.indexOf("? InteractionResult.SUCCESS : InteractionResult.PASS;",
                body.indexOf("if (tuner) {")) > 0,
                "只有目标真的是同步火把 (use 答 true) 才消费这一下, 其余原样放过");
    }

    @Test
    @DisplayName("搬运位的两个新写入入口同样在 CarrierStore.isActive() 之后")
    void nothingIsReachedWithoutABackend() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        assertTrue(bodyOf(groups, "public static CarriedTarget carriedTarget(ServerLevel level,")
                        .contains("!CarrierStore.isActive()"),
                "没有后端时连「有没有这一支」都不问");
        String item = main("item", "FrequencyTunerItem.java");
        assertTrue(bodyOf(item, "private static SyncGroups.CarriedTarget carriedTargetAt(")
                        .contains("!CarrierStore.isActive()"),
                "调频器那一条也一样: Sable 没装时这个类根本不会被加载");
    }

    // ------------------------------------------------------------- helpers --

    private static String main(String area, String name) throws IOException {
        return area.isEmpty()
                ? read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", name)
                : read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", area, name);
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

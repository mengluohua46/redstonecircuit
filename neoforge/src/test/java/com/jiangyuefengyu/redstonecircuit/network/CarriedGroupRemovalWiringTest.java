package com.jiangyuefengyu.redstonecircuit.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;

/**
 * <b>"把同步火把从一台被搬着的宿主里取出来, 那条眼镜线还在" - 整份名单的重发, 以及它凭什么只在少了一个人时发.</b>
 *
 * <p>那条路不走世界 store ({@code CarriedInsert#apply} 只写搬运索引), 于是它一条名单都没发过, 而"这一组还有谁"是
 * 哪个槽位都装不下的一句话: 客户端手里那份名单只能整份换掉。这里钉三件事: 判据本身 (纯值), 接线 (谁在什么条件下
 * 发), 以及客户端那两段缓存会不会因为一份"不含它"的快照而清掉。
 */
class CarriedGroupRemovalWiringTest {

    private static final CarrierId MACHINE = CarrierId.contraption(UUID.randomUUID());
    private static final BlockPos WORLD = new BlockPos(10, 64, 10);
    private static final BlockPos LOCAL = new BlockPos(3, 2, 1);

    @Test
    @DisplayName("判据: 上一次发出去的名单里少了一个成员才算, 多了一个、或者没变都不算")
    void onlyALostMemberCounts() {
        RCNetwork.SyncGroupPayload.SyncGroup before =
                group(7, List.of(WORLD), List.of(carried(LOCAL)));
        assertFalse(HostSync.lostMember(List.of(before), List.of(before)),
                "名单没变: 一个包都不该发");

        RCNetwork.SyncGroupPayload.SyncGroup worldGone =
                group(7, List.of(), List.of(carried(LOCAL)));
        assertTrue(HostSync.lostMember(List.of(before), List.of(worldGone)),
                "世界段少了一个人");

        RCNetwork.SyncGroupPayload.SyncGroup carriedGone =
                group(7, List.of(WORLD), List.of());
        assertTrue(HostSync.lostMember(List.of(before), List.of(carriedGone)),
                "搬运段少了一个人 - 报的就是这一幕: 从被搬着的那一格把火把取出来");

        RCNetwork.SyncGroupPayload.SyncGroup added =
                group(7, List.of(WORLD, WORLD.east()), List.of(carried(LOCAL), carried(LOCAL.east())));
        assertFalse(HostSync.lostMember(List.of(before), List.of(added)),
                "只是多了一个成员: 没人看见过它, 而加进去的那条路自己会发");

        assertTrue(HostSync.lostMember(List.of(before), List.of()),
                "整个频道没了, 而它刚才还有人: 那就是人走了");
        assertFalse(HostSync.lostMember(List.of(), List.of(before)),
                "一份空名单说不了什么, 于是它不可能少人");
    }

    @Test
    @DisplayName("接线: 所有删除的入口都在 HostSync#broadcast 里, 而它在'这一格空了'之后才问名单")
    void theRemovalFunnelAsksForTheList() throws IOException {
        String hostSync = main("network", "HostSync.java");
        String batch = bodyOf(hostSync, "public static void broadcast(ServerLevel level, List<HostEntry> entries) {");
        assertTrue(batch.contains("if (holdsRemoval(entries)) {")
                        && batch.contains("broadcastSyncGroupsIfMemberLeft(level);"),
                "这一批里有一格空了, 就要问一句名单是不是少了一个人: 世界里取出、被搬着的那一格取出元件、活塞搬走、"
                        + "落地、指令, 全部经过这里");
        assertFalse(batch.contains("HostSync.broadcastSyncGroups(level);"),
                "不许改回无条件整份重发: 每次交互都发一个整份包");

        int slotSent = batch.indexOf("PacketDistributor.sendToPlayer(player, payload);");
        int asked = batch.indexOf("broadcastSyncGroupsIfMemberLeft(level);");
        assertTrue(slotSent > 0 && asked > slotSent,
                "'这一格空了'先到, 名单后到: 客户端不会先看到一份'他还是成员'的名单");

        String gate = bodyOf(hostSync, "public static boolean broadcastSyncGroupsIfMemberLeft(ServerLevel level) {");
        int coldStart = gate.indexOf("sent == null");
        int compared = gate.indexOf("if (!lostMember(sent, payload.groups())) {");
        int resent = gate.indexOf("sendGroups(level, payload);");
        assertTrue(coldStart > 0 && compared > coldStart,
                "第一份名单只立底不发 (那时没有谁的缓存需要纠正), 之后才比");
        assertTrue(resent > compared,
                "重发排在判据之后: 这一句就是「只在成员真的少了一个时发」本身");

        String send = bodyOf(hostSync,
                "private static void sendGroups(ServerLevel level, RCNetwork.SyncGroupPayload payload) {");
        assertTrue(send.contains("rememberGroups(level.dimension(), payload);"),
                "发出去的那一份就是下一次比较的底, 于是记账与发包必须在同一处");
        assertTrue(bodyOf(hostSync, "public static void sendSnapshot(ServerPlayer player) {")
                        .contains("rememberGroups(level.dimension(), groups);"),
                "登录时那一条也是客户端手里的一份名单: 它同样要立底");
    }

    @Test
    @DisplayName("被搬着的那一格: 取出只说了'这一格空了', 名单那半句现在由删除那半个入口补上")
    void theCarriedRetrievalReachesTheFunnel() throws IOException {
        String carrier = main("carrier", "CarrierInteraction.java");
        String placement = bodyOf(carrier,
                "private static void broadcastPlacementChange(ServerLevel level, CarriedBlockRemoval.Hit hit,");
        assertTrue(placement.contains("HostSync.broadcast(level, hit.worldPos(), null);"),
                "取出的一整条路上, 客户端听到的只有这一句 (这一格空了)");
        assertFalse(placement.contains("broadcastSyncGroups"),
                "它一条名单都没发过 - 根因就在这里; 修法在 HostSync#broadcast 的删除那一半, 不在这条路上加第二处");

        String forget = bodyOf(main("", "InnerRedstoneInteraction.java"),
                "public static InnerRedstoneNode forget(ServerLevel level, BlockPos pos) {");
        assertTrue(forget.contains("HostSync.broadcast(level, pos, null);")
                        && forget.contains("HostSync.broadcastSyncGroupsIfMemberLeft(level);"),
                "世界那一条取出走同一个闸门: 一条判据, 两条路");
        assertFalse(forget.contains("HostSync.broadcastSyncGroups(level);"),
                "无条件重发那一句已经不在: 上面那句删除广播自己会问");
    }

    @Test
    @DisplayName("客户端那两段缓存: 一份不含它的快照到了就清, 于是那条线不会留在原地")
    void bothClientCachesAreWholeReplacedByOneSnapshot() throws IOException {
        String view = main("client", "SyncGroupView.java");
        String world = bodyOf(view,
                "public static void apply(ResourceKey<Level> dimension, "
                        + "Map<Integer, ? extends Collection<BlockPos>> groups) {");
        assertTrue(world.contains("GROUPS.put(dimension, copy);"), "世界段整份换掉");
        assertTrue(world.contains("CARRIED.put(dimension, Map.of());"),
                "而且把搬运段一起清掉: '这一份快照里没有它'就是清掉, 不必另有一条消息");
        String carried = bodyOf(view, "public static void applyCarried(ResourceKey<Level> dimension,");
        assertTrue(carried.contains("CARRIED.put(dimension, copy);"),
                "搬运段自己也是整份替换, 不是增量");

        String handle = bodyOf(main("network", "RCNetwork.java"),
                "private static void handle(SyncGroupPayload payload, IPayloadContext context) {");
        int applied = handle.indexOf("SyncGroupView.apply(payload.dimension(), groups);");
        int sink = handle.indexOf("sink.accept(payload.dimension(), carried);");
        assertTrue(applied > 0 && sink > applied,
                "世界段先换、搬运段后到 - 顺序就是 apply 清空的那一半: 两段不会来自两个时刻");
    }

    // ------------------------------------------------------------- helpers --

    private static RCNetwork.SyncGroupPayload.SyncGroup group(int channel, List<BlockPos> members,
                                                             List<RCNetwork.SyncGroupPayload.CarriedMember> carried) {
        return new RCNetwork.SyncGroupPayload.SyncGroup(channel, members, carried);
    }

    private static RCNetwork.SyncGroupPayload.CarriedMember carried(BlockPos local) {
        return new RCNetwork.SyncGroupPayload.CarriedMember(MACHINE, local);
    }

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

package com.jiangyuefengyu.redstonecircuit.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>「结构里的那个方块被挖掉, 那条眼镜线还在, 直到整台结构没了才消失」 - 被毁掉的宿主也要问一次名单.</b>
 *
 * <p>报的那一幕: <i>物理方块内火把, 与地面的连接 ... 打掉那个物理方块, 连接的线还在, 打掉活塞才消失</i>。
 *
 * <p>两半原因, 本类各钉一半:
 *
 * <ul>
 *   <li><b>服务端那一半 (缺口)</b>: 被搬着的方块被毁, 走的是 {@code CarrierStore#dropCandidate} ——
 *       {@code index.discard(...)} 之后只说了一句「这一格没了」({@code broadcastCarrier}), <b>整份名单一句都没问</b>。
 *       世界那一侧的孪生路径 ({@code InnerRedstoneInteraction#forget}) 早就问过同一个闸门了, 而这里从来没有: 于是
 *       客户端手里那份名单仍然把 (机器, 机器内坐标) 算作这一组的成员。</li>
 *   <li><b>客户端那一半 (为什么整台结构没了才清)</b>: 画线的人只会把<b>拿得到位姿</b>的机器上的成员换算成世界坐标
 *       ({@code SyncGroupLines.Render#spots}: 位姿为 null 就整台跳过)。一台还在飞的结构每 tick 都在报位姿, 于是名单上
 *       那条成员照旧画得出来; 而载体消失那条消息是<b>没有位姿</b>的, 于是到那一刻才一笔勾销 —— 这正是「打掉活塞才
 *       消失」。</li>
 * </ul>
 *
 * <p>判据本身按<b>整份名单</b>比, 不按键比: 索引里那条记录被换过名字 (provisional → 实体 UUID, 见
 * {@code CarrierStore#reconcile}) 之后, 拿旧名字去删一条都删不到, 而闸门比的是「上一次发出去的那一份里, 有没有一个
 * 成员这一次不在里面了」—— 与名字无关, 于是换键既不会让它漏发, 也不会让它多发。
 */
class CarrierDestroyedMemberTest {

    private static final CarrierId PROVISIONAL =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000004001"));
    private static final CarrierId SETTLED =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000004002"));
    private static final int CHANNEL = 7;
    private static final BlockPos GROUND = new BlockPos(10, 64, 10);
    private static final BlockPos LOCAL = new BlockPos(3, 2, 1);

    /** The world store, as a map - the same stand-in the index's own tests use. */
    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        private final Map<Long, Slot> slots = new HashMap<>();

        @Override
        @Nullable
        public Slot get(BlockPos pos) {
            return slots.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, @Nullable Slot slot) {
            if (slot == null) {
                slots.remove(pos.asLong());
            } else {
                slots.put(pos.asLong(), slot);
            }
        }
    }

    // ------------------------------------------------------------ 纯值: 换键 --

    /**
     * <b>换过键之后再删: 拿旧名字一条都删不到.</b>
     *
     * <p>这不是「删错了」, 而是「删不到」: {@code rekey} 把记录整条搬到新名字下, 旧名字在索引里已经不存在, 于是
     * {@code discard(旧名, local)} 答 {@code null}。而 {@code dropCandidate} 在那一刻会走它的早退分支 —— 不广播、
     * 不摘成员 —— 于是「这一格被毁」这件事对名单来说<b>完全没有发生</b>, 名单一直是满的, 线一直画着。
     *
     * <p>生产路径不会这样问: 它问的是 {@code hitAt} 解析出来的那一个 (投影表与 plot 归属都由<b>此刻</b>的索引建出来),
     * 也就是新名字。下一条断言就是「用新名字能删到」, 它同时是「闸门之后能看见少了一个人」的前提。
     */
    @Test
    @DisplayName("纯值：换键之后旧名字删不到、新名字删得到 —— 这就是「位置/名字变了再删」的全部差别")
    void aRekeyedRecordIsOnlyDeletedUnderItsCurrentName() {
        CarriedHostIndex index = new CarriedHostIndex();
        FakeWorld world = new FakeWorld();
        world.set(GROUND, new Slot(ComponentType.SYNC_TORCH));
        index.claim(PROVISIONAL, LOCAL, GROUND, world);
        assertEquals(1, index.liveHostsOf(PROVISIONAL).size(), "一支内嵌的同步火把骑在这台机器上");

        assertEquals(1, index.rekey(PROVISIONAL, SETTLED), "provisional → 实体 UUID, 记录整条搬过去");
        assertTrue(index.hostsOf(PROVISIONAL).isEmpty(), "旧名字在索引里一条记录都不剩");

        assertNull(index.discard(PROVISIONAL, LOCAL),
                "拿旧名字去删: 一条都删不到 —— 走这条路就等于「这一格没被毁」, 名单不会被摘, 线一直画着");
        assertTrue(index.carriers().contains(SETTLED), "而记录其实还在, 只是住在另一个名字下");

        CarriedHost removed = index.discard(SETTLED, LOCAL);
        assertNotNull(removed, "用此刻的名字 (hitAt 解析出来的那一个) 才删得到");
        assertNotNull(removed.slot(), "删掉的那个宿主里就是那一支火把");
        assertEquals(ComponentType.SYNC_TORCH, removed.slot().type,
                "于是组 pass 的名单里也少了它");
        assertTrue(index.liveHostsOf(SETTLED).isEmpty(), "此刻这台机器上不再有成员");
    }

    // ------------------------------------------------------- 纯值: 名单闸门 --

    /**
     * 闸门问的是「上一次发出去的那一份里, 有没有一个成员这一次不在里面了」, 与名字、与是不是同一次重命名都无关。
     *
     * <p>三种形状必须给同一个答案 (发): 搬运段少了一个人 (这一幕)、整个频道没了、以及<b>成员换了名字</b> ——
     * 后者是重命名那条路的形状, 它让闸门多发一个包 (安全方向), 绝不会让它漏发。
     */
    @Test
    @DisplayName("纯值：被毁掉的那个成员在名单里消失了, 于是闸门发；名单没变就不发")
    void theGateJudgesTheWholeList() {
        RCNetwork.SyncGroupPayload.SyncGroup sent =
                group(List.of(GROUND), List.of(carried(PROVISIONAL, LOCAL)));

        assertTrue(HostSync.lostMember(List.of(sent),
                        List.of(group(List.of(GROUND), List.of()))),
                "搬运段少了一个人 - 报的就是这一幕: 结构里那个方块被毁, 地上那一支还在");
        assertTrue(HostSync.lostMember(List.of(sent), List.of()),
                "整个频道都没了, 而它刚才还有成员: 那就是人走了");
        assertTrue(HostSync.lostMember(List.of(sent),
                        List.of(group(List.of(GROUND), List.of(carried(SETTLED, LOCAL))))),
                "名单是在重命名之前发出去的那一份: 名字换了也算「旧的那个不在了」, 于是只会多发, 不会漏发");
        assertFalse(HostSync.lostMember(List.of(sent), List.of(sent)),
                "名单一个字都没变: 一个包都不该发 (不许退回无条件重发)");
        assertFalse(HostSync.lostMember(List.of(sent),
                        List.of(group(List.of(GROUND, GROUND.east()),
                                List.of(carried(PROVISIONAL, LOCAL), carried(PROVISIONAL, LOCAL.east()))))),
                "只是多了一个成员: 没人看见过它, 而加进去的那条路自己会发");
    }

    // ---------------------------------------------------------------- 接线 --

    /**
     * 缺的那一句就在这里: {@code dropCandidate} 摘掉记录、发掉载体消息, 然后<b>问一次名单</b>。
     *
     * <p>三条顺序都是判据的一部分: 摘成员在前 (闸门读的名单是从这个索引建出来的), 载体消息在前 (客户端要先知道
     * 「这一格不是宿主了」, 再知道「它也不是成员了」), 而最后那一句必须是闸门而不是整份重发。
     */
    @Test
    @DisplayName("接线：结构里被毁的方块走 dropCandidate，它摘成员、发载体消息，然后才问名单闸门")
    void theDestroyedHostAsksTheGroupGate() throws IOException {
        String source = flat(main("carrier", "CarrierStore.java"));
        String body = bodyOf(source, "private static Slot dropCandidate(ServerLevel level, "
                + "CarriedBlockRemoval.Destroyed destroyed, String why)");

        assertTrue(body.contains("HostSync.broadcastSyncGroupsIfMemberLeft(level);"),
                "被毁掉的宿主也可能是某个频道的一个成员, 而「这一组还有谁」是哪个槽位都装不下的一句话: "
                        + "这条路必须问一次名单闸门, 否则客户端那条线只能等整台结构消失才消失");
        assertTrue(offsetOf(body, "index.discard(destroyed.carrier()")
                        < offsetOf(body, "HostSync.broadcastSyncGroupsIfMemberLeft(level);"),
                "摘成员在前: 闸门读的名单是从这个索引建出来的, 记录还在时它会答「没少人」");
        assertTrue(offsetOf(body, "broadcastCarrier(level, destroyed.carrier()")
                        < offsetOf(body, "HostSync.broadcastSyncGroupsIfMemberLeft(level);"),
                "载体消息在前、名单在后: 客户端先听到「这一格不是宿主了」, 再听到「它也不是成员了」");
        assertTrue(offsetOf(body, "if (slot != null && slot.type.isGroupDriven()) {")
                        < offsetOf(body, "HostSync.broadcastSyncGroupsIfMemberLeft(level);"),
                "同一个判据只在调用点问一次, 而它是名单自己收人的那一条 (slot.type.isGroupDriven): 一根粉、一台中继器被毁时"
                        + "根本不可能少一个成员, 而一次整份名单扫描挂在自愈清扫的每 tick 上");
        assertFalse(body.contains("HostSync.broadcastSyncGroups(level);"),
                "不许退回无条件整份重发: 这一句每次破坏都会多发一个整份包");

        // 同一件事在世界上那一侧早就做了: 一条判据, 两条路。
        String worldSide = bodyOf(flat(main("", "InnerRedstoneInteraction.java")),
                "public static InnerRedstoneNode forget(ServerLevel level, BlockPos pos)");
        assertTrue(worldSide.contains("if (node.type() == ComponentType.SYNC_TORCH) {")
                        && worldSide.contains("HostSync.broadcastSyncGroupsIfMemberLeft(level);"),
                "世界里的孪生路径 (挖掉宿主方块) 问的就是这一句, 而且只在那个元件是同步火把时问; 被搬着的那一侧缺的正是它的镜像");

        // 这条路确实由 BreakEvent 与自愈清扫共用, 于是两种「方块没了」都经过它。
        assertTrue(bodyOf(source, "public static Slot dropDestroyedHost(ServerLevel level, BlockPos pos)")
                        .contains("dropCandidate(level, destroyed, \"was broken\")"),
                "BreakEvent 那条入口");
        assertTrue(source.contains("dropCandidate(level, destroyed, \"found gone by the self-healing sweep\")"),
                "自愈清扫那条入口 (没有 BreakEvent 的方块被毁 - 例如别的模组挖掉它)");
    }

    /**
     * 「打掉活塞才消失」的另一半: 客户端画线时只会把拿得到位姿的机器上的成员换算成世界坐标。
     *
     * <p>这台结构还在飞的时候每 tick 都报位姿, 于是名单上那条成员照旧画得出来 —— 名单错了, 线就错; 而载体消失那条
     * 消息 (位姿为 null) 一来, 整台机器的成员一起被跳过, 于是线才消失。**这就是为什么把活塞打掉 (整台结构随之不成立)
     * 能把它清掉, 而名单本身一直是错的**: 症状被位姿那道闸门盖住了。
     */
    @Test
    @DisplayName("接线：客户端画线的成员只来自名单，而拿不到位姿的那台机器整台跳过")
    void theClientDrawsFromTheListAndSkipsACarrierWithNoPose() throws IOException {
        String body = bodyOf(flat(main("client", "SyncGroupLines.java")),
                "private static List<Spot> spots(ClientLevel level, int channel, float partialTick, "
                        + "Collection<BlockPos> world, Loaded loaded)");

        assertTrue(body.contains("SyncGroupView.carriedOf(level.dimension(), channel)"),
                "被搬着的那些成员只从服务端那份名单里来, 客户端不自己扫宿主表");
        assertTrue(body.contains("if (pose == null) {"),
                "拿不到位姿的机器整台跳过 - 于是「结构没了」能在名单还错着的时候就把线清掉");
        assertTrue(offsetOf(body, "if (pose == null) {") < offsetOf(body, "spots.add(Spot.carried("),
                "跳过发生在换算之前: 这不是「跳过一条线」, 是「这台机器这一帧一个成员都不算」");
    }

    // --------------------------------------------------------------- 工具 --

    private static RCNetwork.SyncGroupPayload.SyncGroup group(
            List<BlockPos> members, List<RCNetwork.SyncGroupPayload.CarriedMember> carried) {
        return new RCNetwork.SyncGroupPayload.SyncGroup(CHANNEL, members, carried);
    }

    private static RCNetwork.SyncGroupPayload.CarriedMember carried(CarrierId carrier, BlockPos local) {
        return new RCNetwork.SyncGroupPayload.CarriedMember(carrier, local);
    }

    private static String main(String area, String name) throws IOException {
        return area.isEmpty()
                ? read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", name)
                : read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", area, name);
    }

    private static String read(String... parts) throws IOException {
        Path relative = Path.of("", parts);
        Path underNeoforge = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }

    /** 一条方法体, 用括号配平定位, 于是判据不会悄悄漂走。 */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "源码里已经没有 '" + signature + "'; 这条判据过期了");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "'" + signature + "' 没有方法体");
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
        throw new AssertionError("'" + signature + "' 之后括号不配平");
    }

    /** {@code anchor} 在 {@code source} 里的偏移; 找不到就失败, 于是拼错的锚点不会白送通过。 */
    private static int offsetOf(String source, String anchor) {
        int offset = source.indexOf(anchor);
        assertTrue(offset >= 0, "判据的锚点 \"" + anchor + "\" 已经不在源码里了");
        return offset;
    }

    /** 去掉排版差异的源码, 于是子串判据不依赖换行与缩进。 */
    private static String flat(String source) {
        return source.replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}

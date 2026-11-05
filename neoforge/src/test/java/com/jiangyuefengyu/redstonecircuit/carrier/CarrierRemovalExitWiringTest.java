package com.jiangyuefengyu.redstonecircuit.carrier;

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

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「载体消失」这件事只有一个出口，以及整份快照到达时客户端会丢掉不再存在的载体。
 *
 * <p>诊断原文：{@code client holds 4 carrier(s) but the server last reported 3 - a removal was not
 * announced}。客户端手里的载体只可能是某一条 carrier 消息给的, 而服务端"还有几台"读的是索引
 * ({@code CarriedHostIndex#carriers})。于是少发一次删除只有一种形状: <b>一台已经不在索引里的载体, 还被一条带位姿的
 * 消息说起过</b> —— 客户端把位姿存下来, 之后再没有任何消息提它, 服务端也没有谁能为它回答位姿。这条测试把那个判据
 * (索引里还有没有它的记录) 和它的唯一出口钉在源码上, 并且把"整份快照会丢掉不再存在的载体"钉成纯值。
 *
 * <p>本类只验三件事: 判据本身(纯值)、每条删除通路的接线(源码)、整份快照的替换语义(纯值)。
 */
class CarrierRemovalExitWiringTest {

    private static final CarrierId MINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000003001"));
    private static final CarrierId THEIRS =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000003002"));
    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse("redstonecircuit:p130exit"));

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

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static CarrierPose poseAt(double x, double y, double z) {
        return CarrierPose.of(new Vector3d(x, y, z), new Quaterniond());
    }

    // ------------------------------------------------------------------ 判据 --

    /**
     * 判据是"索引里还有没有它的记录", 而不是"它还搬着东西"。这两件事必须给出两个答案: 前者是载体消失(必须发删除),
     * 后者只是它把东西放下了(记录还在, 还该被数进服务端的台数) —— 弄反了就是把一次正常落地说成载体消失。
     */
    @Test
    @DisplayName("判据：最后一个记录被毁掉后载体就不再被索引登记，而放下东西之后仍然登记")
    void theIndexDecidesWhetherACarrierStillExists() {
        CarriedHostIndex index = new CarriedHostIndex();
        FakeWorld world = new FakeWorld();

        BlockPos local = pos(1, 0, 2);
        BlockPos origin = pos(10, 64, 10);
        world.set(origin, new Slot(ComponentType.TORCH));
        index.claim(MINE, local, origin, world);
        world.set(pos(11, 64, 10), new Slot(ComponentType.DUST));
        index.claim(THEIRS, pos(1, 0, 2), pos(11, 64, 10), world);
        assertTrue(index.carriers().contains(MINE), "两条记录, 两台载体");

        assertNotNull(index.discard(MINE, local), "最后一个记录被毁掉");
        assertTrue(index.hostsOf(MINE).isEmpty(), "于是索引里它一条记录都没有了");
        assertFalse(index.carriers().contains(MINE),
                "也就是服务端「还有几台载体」里不再有它 —— 这正是「必须发一次删除」的那一刻");
        assertTrue(index.carriers().contains(THEIRS), "另一台一根头发都没少");

        // 对照: 落地走的是另一条路 —— 记录被标成 landed, 留在索引里, 因此绝不能被读成"载体消失"。
        CarriedHostIndex second = new CarriedHostIndex();
        FakeWorld secondWorld = new FakeWorld();
        secondWorld.set(origin, new Slot(ComponentType.REPEATER));
        second.claim(THEIRS, local, origin, secondWorld);
        assertTrue(second.release(THEIRS, local, null, secondWorld).wrote(), "一个正常的落地");
        assertFalse(second.hostsOf(THEIRS).isEmpty(),
                "落地的记录留在索引里, 所以「放下东西」不是「载体没了」: 拿 hostsOf 为空当判据才对");
        assertTrue(second.carriers().contains(THEIRS), "它仍然被数进服务端台数, 一条删除都不欠");
        assertTrue(second.liveHostsOf(THEIRS).isEmpty(), "只是不再有东西骑着了");
    }

    // ------------------------------------------------------------ 整份快照 --

    /**
     * 客户端那一半: 整份快照是"替换整张表", 不是"更新提到的那些"。少发一次删除的另一种补救就是它 —— 一份不再提到某台
     * 载体的快照必须把那台载体从客户端手里拿走, 否则"整份"这个词就没有意义。
     */
    @Test
    @DisplayName("整份快照到达时，客户端丢掉不再被它提到的载体（位姿与它搬着的宿主一起）")
    void aSnapshotDropsTheCarriersItNoLongerNames() {
        CarrierIndex index = new CarrierIndex();
        CarrierIndex.CarriedView mine = new CarrierIndex.CarriedView(MINE, pos(1, 0, 2),
                pos(10, 64, 10), pos(10, 64, 10), new Slot(ComponentType.TORCH));
        CarrierIndex.CarriedView theirs = new CarrierIndex.CarriedView(THEIRS, pos(2, 0, 2),
                pos(20, 64, 10), pos(20, 64, 10), new Slot(ComponentType.DUST));

        index.applySnapshot(DIMENSION, List.of(mine, theirs),
                Map.of(MINE, poseAt(10.5, 64.0, 10.5), THEIRS, poseAt(20.5, 64.0, 10.5)));
        assertEquals(2, index.carrierCount(), "两份位姿, 两台载体");

        index.applySnapshot(DIMENSION, List.of(mine), Map.of(MINE, poseAt(10.5, 64.0, 10.5)));
        assertEquals(1, index.carrierCount(), "第二份快照只提到一台, 于是只剩一台");
        assertNull(index.poseOf(THEIRS), "被漏掉的那台连位姿一起没了");
        assertTrue(index.viewsOf(THEIRS).isEmpty(), "它搬着的宿主也没有留在任何桶里");
        assertEquals(1, index.size(), "整张宿主表就是快照说的那一份");
        assertEquals(1, index.settledCarrierCount(),
                "客户端自己的台数于是和服务端读的台数是同一个数 —— 诊断那一句不再成立");
    }

    // -------------------------------------------------------------- 接线 --

    /**
     * 唯一的出口: 一条带位姿的 carrier 消息只允许给"索引里还登记着"的载体, 否则它必须走 {@code forget}。服务端
     * 只有两处裸发 carrier 消息 —— 这一处和 {@code announceRemoval} 里那条 null 位姿的 —— 所以任何删除路径都绕不开它。
     */
    @Test
    @DisplayName("一条带位姿的载体消息，只发给索引里还登记着的载体；不在索引里的走 forget")
    void aPosedMessageIsNeverSentForACarrierThatLeftTheIndex() throws IOException {
        String source = flat(readMain("carrier", "CarrierStore.java"));
        String body = methodBody(source, "private static void broadcastCarrier(ServerLevel level, "
                + "CarrierId carrier, @Nullable CarriedHostIndex index, @Nullable CarrierPose pose, "
                + "List<BlockPos> removed)");

        assertTrue(body.contains("pose != null && !filesCarrier(index, carrier)"),
                "判据必须在出口里: 带位姿 + 索引里没有记录 = 一条会把载体永久留在客户端的消息");
        assertTrue(body.contains("forget(level, carrier," ),
                "这种消息要改走 forget —— 「它没了」只有一个出口");
        assertTrue(offsetOf(body, "forget(level, carrier,")
                        < offsetOf(body, "HostSync.broadcastCarrier(level, carrier, index, pose, removed)"),
                "而且是在裸发之前: 顺序反过来就等于先说了它还在, 再补一句它没了");
        assertTrue(body.contains(", removed);"),
                "被丢弃的宿主坐标跟着这条删除一起走, 否则客户端会把它们当落地记录继续画");

        assertEquals(2, count(source, "HostSync.broadcastCarrier("),
                "服务端只有两处裸发 carrier 消息: 上面这个出口, 和 announceRemoval 里那条 null 位姿的删除");
        String removal = methodBody(source, "private static int announceRemoval(ServerLevel level, "
                + "CarrierId carrier, @Nullable String reason, @Nullable List<BlockPos> removed)");
        assertTrue(removal.contains("HostSync.broadcastCarrier(level, carrier, indexOf(level), null,"),
                "另一处只发 null 位姿: 它是删除本身, 不是「它还在这里」");
    }

    /**
     * 每条会让载体离开索引的路, 都在源码里明确通向那个出口。逐条点名, 于是少接一条就是一条断言失败, 而不是一句
     * "大概都接好了"。
     */
    @Test
    @DisplayName("四条移除路径（落地收割 / 整体释放 / 重命名 / 孤儿清扫）都通向 forget")
    void everyCarrierRemovalPathReachesTheOneExit() throws IOException {
        String source = flat(readMain("carrier", "CarrierStore.java"));

        assertTrue(source.contains("for (CarrierId finished : index.forgetReleased()) {"),
                "落地收割: 记录清空之后载体离开索引");
        assertTrue(offsetOf(source, "for (CarrierId finished : index.forgetReleased()) {")
                        < offsetOf(source, "forget(level, finished, "),
                "收割在同一个块里立刻 announce, 而不是等下一趟");

        assertTrue(source.contains("int announced = forget(level, carrier, reason);"),
                "整体释放: releaseAll 无条件走 forget, 不管有没有东西被搬回来");

        assertTrue(source.contains("forget(level, resolution.present().carrier(),"),
                "重命名: 旧名字的记录被搬空之后也要发一次删除 (rekey 已经把旧名字从索引里删掉了)");

        assertTrue(source.contains("releaseAll(level, carrier, \"structure is gone (orphan sweep)\");"),
                "孤儿清扫走 releaseAll, 于是也走 forget");

        assertTrue(source.contains("if (index.hostsOf(carrier).isEmpty()) {")
                        || source.contains("!filesCarrier(index, carrier)"),
                "毁掉最后一个记录那条路不在上面四条里: 它经过出口的判据, 见上一个测试");
    }

    /**
     * 毁掉最后一个记录的那条路({@code dropCandidate})本来发的是"带位姿 + 这个坐标没了"。它现在仍然这样发, 于是由出口
     * 的判据把它改成删除 —— 这一条钉住"改的是出口, 不是改在这里", 也就是"只有一处判据"。
     */
    @Test
    @DisplayName("dropCandidate 仍走同一个出口，于是毁掉最后一个记录时由出口改判成删除")
    void theDestroyedLastHostIsHandledByTheExit() throws IOException {
        String source = flat(readMain("carrier", "CarrierStore.java"));
        String body = methodBody(source, "private static Slot dropCandidate(ServerLevel level, "
                + "CarriedBlockRemoval.Destroyed destroyed, String why)");

        assertTrue(body.contains("CarriedHost removed = index.discard(destroyed.carrier(), "
                        + "destroyed.localPos());"),
                "记录先被拿掉 —— 这一步可能就是载体离开索引的那一步");
        assertTrue(body.contains("broadcastCarrier(level, destroyed.carrier(), index, "
                        + "poseOf(level, destroyed.carrier()), List.of(destroyed.localPos()));"),
                "然后才广播: 位姿照旧问结构, 因为结构往往还在飞 —— 判据留给出口");
        assertFalse(body.contains("HostSync.broadcastCarrier("),
                "这条路不许绕开出口自己发包, 否则出口的判据对它无效");
    }

    /**
     * 整份快照的适用场合: 一个玩家到达时发, 空的一份也发。空的一份是"这边一台都没有"的唯一直说, 而不是可省的包。
     */
    @Test
    @DisplayName("到达玩家时整份快照总是发，空的也发")
    void theArrivalSnapshotIsSentEvenWhenEmpty() throws IOException {
        String source = flat(readMain("network", "HostSync.java"));
        String body = methodBody(source, "public static void sendCarrierSnapshot(ServerPlayer player)");

        assertTrue(body.contains("PacketDistributor.sendToPlayer(player, payload);"),
                "整份候补消息照旧发");
        assertFalse(body.contains("carriers().isEmpty()"),
                "不再因为「一台都没有」就提前返回: 换维度到一个没有载体的维度时, 那份空名单正是客户端需要的唯一一句话");
        assertTrue(body.contains("CarrierStore.noteSnapshotSent(level, named);"),
                "账本照样推进, 于是「发出去的那一份」与客户端手里那份仍然是一回事");
    }

    // ------------------------------------------------------------------ 工具 --

    /** 一条方法体, 用括号配平定位, 于是判据不会悄悄漂走。 */
    private static String methodBody(String source, String signature) {
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

    /**
     * {@code anchor} 在 {@code source} 里的偏移; 找不到就失败。每个偏移都要拿去比大小, 而 {@code -1}
     * 会让任何比较白送通过 —— 于是拼错的锚点不会失败, 只会悄悄不再守卫。
     */
    private static int offsetOf(String source, String anchor) {
        int offset = source.indexOf(anchor);
        assertTrue(offset >= 0, "判据的锚点 \"" + anchor + "\" 已经不在源码里了, 于是建立在它上面的比较会白送通过");
        return offset;
    }

    /** {@code needle} 在 {@code source} 里出现了几次。 */
    private static int count(String source, String needle) {
        int total = 0;
        int at = source.indexOf(needle);
        while (at >= 0) {
            total++;
            at = source.indexOf(needle, at + needle.length());
        }
        return total;
    }

    /** 去掉排版差异的源码, 于是子串判据不依赖换行与缩进。 */
    private static String flat(String source) {
        return source.replace("\r\n", "\n").replaceAll("\\s+", " ");
    }

    /** 找到本 mod 的一份 main 源码; 找不到就大声失败, 而不是悄悄跳过这条判据。 */
    private static String readMain(String packagePath, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                packagePath, fileName);
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, absolute)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + " and " + absolute + "); this guard reads the source, so it "
                + "must not be skipped silently");
    }
}

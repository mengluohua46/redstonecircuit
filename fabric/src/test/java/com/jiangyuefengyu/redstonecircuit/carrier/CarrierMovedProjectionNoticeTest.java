package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「把方块移开（结构整体移动/旋转），元件原来被画出来的那一格留下幽灵」：投影位置变了以后，旧的那一格没人删。
 *
 * <p>一个元件放进被搬着的方块里时，服务端发两份记录：carrier 记录（键是结构自己的 local 坐标）和一条<b>世界路径</b>
 * 的 host（键是这台宿主被<b>投影</b>到的那一格，见 {@code CarrierInteraction#broadcastPlacementChange}）。第二条记在
 * 服务端 store 从来没有过的坐标上，所以只有<b>说得出的那几条路</b>能把它收回去：打掉（{@code CarrierStore#dropCandidate}）、
 * 取出（同一条 {@code broadcastPlacementChange} 的另一半）。<b>搬走这条路上什么都没有</b>——而一台飘走或转过的机器
 * 把它搬着的每一个宿主都换了一格投影，于是每一格都留下一条只有客户端认得的记录，退出重进就没了（服务端那一格本来
 * 就是空的）。
 *
 * <p>本类钉三件事：这句删除落在投影变化那一趟里（源码接线）、它<b>一次发一份</b>并沿用世界 store 那条判据（源码接线），
 * 以及「走过的格与转过的格必须分开」（纯值）——转过的宿主还在原来那一格上，把它说成空了就是把一句仍然成立的话收回去。
 */
class CarrierMovedProjectionNoticeTest {

    private static final CarrierId CARRIER =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-0000000040e1"));
    private static final ResourceKey<Level> DIMENSION =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("redstonecircuit:test"));

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static CarrierPose poseAt(double x, double y, double z) {
        return CarrierPose.at(new Vector3d(x, y, z));
    }

    private static CarrierProjection.ProjectedHost host(BlockPos local, BlockPos world, CarrierPose pose,
                                                        int power) {
        Slot slot = new Slot(ComponentType.CLOCK);
        slot.power = power;
        return new CarrierProjection.ProjectedHost(CARRIER, local, world, pose, slot, power);
    }

    /**
     * 这一句删除落在<b>唯一那一趟</b>里：{@code CarrierStore#notifyProjectionChanges}。修复前这条断言红：那一趟只把走过
     * 的格交给 {@code notifyWorld}（让世界重读信号），没有任何一句话告诉客户端。
     */
    @Test
    @DisplayName("结构搬走：旧投影位置要经由投影变化那一趟被说成空了")
    void aStructureThatMovedAwayWithdrawsTheOldProjection() throws IOException {
        String pass = body(sourceOf("carrier", "CarrierStore.java"),
                "private static void notifyProjectionChanges(",
                "private static void notifyStructureNow(");
        assertTrue(pass.contains("withdrawLeftWorldEntries(level, left)"),
                "the pass that already knows which positions the projection left has to turn them into a "
                        + "message: nothing else in the feature ever names an old projected position again");
        assertTrue(pass.contains("left::add"),
                "and it is the leave half of the walk that fills the list - the same positions it hands to "
                        + "notifyWorld, asked for by the client-facing reason");
        assertTrue(pass.contains("forEachChange(dimension, pos -> notifyWorld(level, pos),"),
                "the signal half stays exactly where it was: the world beside the position has to re-read too");

        String withdraw = body(sourceOf("carrier", "CarrierStore.java"),
                "private static void withdrawLeftWorldEntries(",
                "private static void notifyStructureNow(");
        assertTrue(withdraw.contains("HostSync.publishWorldEntries(level, left)"),
                "one message for the whole batch: a machine that drifts over twenty hosts leaves twenty "
                        + "positions, and twenty packets per move is the thing this avoids");
        assertFalse(withdraw.contains("HostRules.itemFor"),
                "and nothing is dropped here - the drop belongs to a destruction, and a structure that moved "
                        + "away destroyed nothing");
    }

    /**
     * 判据只有一条，而且是一次发一份。修复前这条断言红：{@code HostSync} 里没有 {@code publishWorldEntries}，那句
     * 「世界 store 还持有那一格就别动它」也就没得可查。
     */
    @Test
    @DisplayName("批量那一句沿用世界 store 的判据，并且只发一个包")
    void theWithdrawalKeepsTheWorldHostsGuardAndGoesInOneMessage() throws IOException {
        String batch = body(sourceOf("network", "HostSync.java"),
                "public static void publishWorldEntries(",
                "Tells clients everything they need to know about one moving structure");
        assertTrue(batch.contains("slotAt(worldPos) != null"),
                "a position the world store really holds a component at belongs to the ordinary world sync; a "
                        + "removal sent there would erase a component the player really has - and the guard stays "
                        + "in HostSync, where the single-position form has always had it");
        assertTrue(batch.contains("new HostEntry(worldPos.immutable(), null)"),
                "the message says \"nothing here\", which is the null slot - not the component");
        assertTrue(batch.contains("broadcast(level, entries)"),
                "and it leaves through the one exit, once, with everything the pass found");
        assertFalse(batch.contains("broadcastRemoval(level, worldPos)"),
                "a packet per position would undo the point of the batch");
    }

    /**
     * 纯值：走过的格与仍在的格必须是两个答案。一台飘走的机器把宿主从 P1、P2 搬到 P3，客户端在 P1、P2 上那一份记录要
     * 收回去；而 P3 不能收到删除——它正是这台机器现在被画的格。
     */
    @Test
    @DisplayName("投影走过的格被单独报出来，而信号那一路一格都不少")
    void theWalkReportsThePositionsTheStructureLeft() {
        CarrierProjection projection = new CarrierProjection();
        CarrierPose pose = poseAt(10.0, 64.0, 10.0);
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(pos(1, 0, 2), pos(11, 64, 12), pose, 15));
        projection.put(DIMENSION, host(pos(2, 0, 2), pos(12, 64, 12), pose, 15));

        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(pos(9, 0, 2), pos(19, 64, 12), pose, 15));

        List<BlockPos> world = new ArrayList<>();
        List<BlockPos> left = new ArrayList<>();
        projection.forEachChange(DIMENSION, world::add, null, null, left::add);

        // The walk reads a long-keyed map, so the order is the map's and not the insertion's: this is the
        // set of positions, and it is asked as one.
        assertEquals(2, left.size(),
                "exactly the two positions the structure is no longer drawn at: the two records the client is "
                        + "still holding");
        assertTrue(left.containsAll(List.of(pos(11, 64, 12), pos(12, 64, 12))),
                "and they are the positions it left, not the one it arrived at");
        assertTrue(world.containsAll(left),
                "and the signal pass keeps them: the world beside those positions has to re-read as well");
        assertTrue(world.contains(pos(19, 64, 12)),
                "the position the host arrived at is announced, and it is NOT withdrawn");
        assertFalse(left.contains(pos(19, 64, 12)),
                "withdrawing the position a host is drawn at would delete the very record that draws it");
    }

    /**
     * 纯值：转过 180 度但仍然停在<b>同一格</b>上的宿主不是「走过的格」。它仍然被投影在那里，删掉那一格就是把一句仍然
     * 成立的话收回去——而且这台机器转一圈回来时，客户端会有一整段什么都不画。
     */
    @Test
    @DisplayName("只是转过的宿主不算走过：那一格不能被收回")
    void aHostThatOnlyTurnedIsNeverWithdrawn() {
        CarrierProjection projection = new CarrierProjection();
        BlockPos worldPos = pos(11, 64, 12);
        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(pos(1, 0, 2), worldPos, poseAt(10.0, 64.0, 10.0), 15));

        projection.begin(DIMENSION, true);
        projection.put(DIMENSION, host(pos(1, 0, 2), worldPos,
                CarrierPose.of(new Vector3d(10.0, 64.0, 10.0), new Quaterniond().rotateY(Math.PI)), 15));

        List<BlockPos> world = new ArrayList<>();
        List<BlockPos> left = new ArrayList<>();
        projection.forEachChange(DIMENSION, world::add, null, null, left::add);

        assertTrue(left.isEmpty(),
                "the host is projected at exactly the same position: nothing has left it");
        assertTrue(world.contains(worldPos),
                "the position still has to be woken, because the emitted directions rotated");
    }

    /**
     * 现场诊断那条线：它必须<b>同时</b>受 {@code debugLog} 与降频窗口约束。把它改成无条件打印（这台机器每 tick
     * 都会产生这个形状）时，方法体里第一句那层门就没有了，这条断言当场红；改成「窗口只管计数不管打印」时，
     * {@code addsThisWindow != found} 那句提前返回没有了，也红。
     */
    @Test
    @DisplayName("ghost 诊断：debugLog 门控 + 每窗口一行，而且只读不写")
    void theGhostAddDiagnosticIsGatedAndRateLimited() throws IOException {
        String exit = body(sourceOf("network", "HostSync.java"),
                "public static void broadcast(ServerLevel level, List<HostEntry> entries) {",
                "private static boolean holdsRemoval(List<HostEntry> entries) {");
        assertTrue(exit.contains("noteAddWhereNoHostStands(level, entries)"),
                "the diagnostic has to sit at the one exit every host message leaves through - a projection add "
                        + "and a level-tick clock edge both end up here, which is the whole point of the line");

        String trace = body(sourceOf("network", "HostSync.java"),
                "private static void noteAddWhereNoHostStands(",
                "/** How long one window of");
        assertTrue(trace.contains("if (!RCConfig.debugLog())"),
                "the operator's switch is the first thing asked: this line is for a reproduction, not for "
                        + "everybody's log");
        assertTrue(trace.contains("entry.slot() == null || store.slotAt(entry.pos()) != null"),
                "and it names only the shape being hunted - an ADDITION at a cell the world store has nothing "
                        + "at; a removal for an unstored cell is the ordinary, correct message");
        assertTrue(trace.contains("now - addWindowStart >= ADD_WITHOUT_HOST_WINDOW_TICKS"),
                "the window rolls over on game time, which is what keeps a moving machine to one line per window");
        assertTrue(trace.contains("if (addsThisWindow != found)"),
                "and inside a window every further message is counted and returns: without this the window "
                        + "would only re-print the first line's number");
        assertEquals(1, trace.split("RCConfig\\.LOGGER\\.info", -1).length - 1,
                "exactly one print point, so no second ungated line can be added beside it");
        assertFalse(trace.contains("markDirty") || trace.contains("broadcast("),
                "and it writes nothing: the packet has already gone by the time this runs, and a diagnostic that "
                        + "touches the store would be a behaviour");
    }

    // ------------------------------------------------------------------ utils --

    private static String sourceOf(String... parts) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit");
        for (String part : parts) {
            relative = relative.resolve(part);
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(relative);
        candidates.add(Path.of("neoforge").resolve(relative));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath());
    }

    private static String body(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue(from >= 0, "the source no longer contains: " + start);
        int to = source.indexOf(end, from);
        assertTrue(to > from, "the source no longer ends that region with: " + end);
        return source.substring(from, to);
    }
}

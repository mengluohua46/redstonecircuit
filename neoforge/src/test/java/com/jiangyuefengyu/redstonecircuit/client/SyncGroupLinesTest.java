package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierFrame;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule;

import net.minecraft.core.BlockPos;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 同步火把's group as geometry: which blocks one channel joins, and how many lines that is.
 *
 * <p>Every member of a channel is joined to every other, so the count is a function of the member count alone -
 * n(n-1)/2 - and this is the one place that says so. The members arrive as a set from the server, so the
 * picture must not depend on the order they were iterated in, and a member the client has no chunk for must
 * drop out of its own pairs rather than out of the frame.
 */
class SyncGroupLinesTest {

    private static final int CHANNEL = 7;

    /** Members from triples of coordinates, so a test reads as the blocks it is about. */
    private static List<BlockPos> at(int... coordinates) {
        List<BlockPos> members = new ArrayList<>();
        for (int i = 0; i + 2 < coordinates.length; i += 3) {
            members.add(new BlockPos(coordinates[i], coordinates[i + 1], coordinates[i + 2]));
        }
        return members;
    }

    private static List<SyncGroupLines.Segment> lines(int channel, List<BlockPos> members) {
        return SyncGroupLines.lines(channel, members, SyncGroupLines.Loaded.ALL);
    }

    /** Each segment as an unordered pair of endpoints: "the same picture" does not depend on which end came first. */
    private static Set<String> pairs(List<SyncGroupLines.Segment> segments) {
        Set<String> out = new HashSet<>();
        for (SyncGroupLines.Segment segment : segments) {
            String from = text(segment.x1(), segment.y1(), segment.z1());
            String to = text(segment.x2(), segment.y2(), segment.z2());
            out.add(from.compareTo(to) <= 0 ? from + " | " + to : to + " | " + from);
        }
        return out;
    }

    private static String text(double x, double y, double z) {
        return x + "," + y + "," + z;
    }

    @Test
    @DisplayName("a channel of n members is n(n-1)/2 lines, and fewer than two members is none")
    void everyPairOfMembersIsOneLine() {
        assertEquals(0, lines(CHANNEL, at()).size(), "a channel nobody joined has nothing to connect");
        assertEquals(0, lines(CHANNEL, at(0, 64, 0)).size(), "one torch is a group of one: there is no pair");
        assertEquals(1, lines(CHANNEL, at(0, 64, 0, 4, 64, 0)).size(), "two members are one line");
        assertEquals(3, lines(CHANNEL, at(0, 64, 0, 4, 64, 0, 9, 64, 2)).size(),
                "three members are three lines, not two: every pair is joined, not just the neighbours");
        assertEquals(6, lines(CHANNEL, at(0, 64, 0, 4, 64, 0, 9, 64, 2, 2, 70, 5)).size(),
                "and four are six, which is the shape of the formula rather than a number someone typed");
    }

    @Test
    @DisplayName("an unbound torch joins nothing, whatever the member list says")
    void anUnboundChannelDrawsNothing() {
        assertEquals(0, lines(SyncTorchRule.UNBOUND, at(0, 64, 0, 4, 64, 0)).size(),
                "UNBOUND is 'there is a torch here, bound to no group': there is nothing to draw");
        assertEquals(0, lines(0, at(0, 64, 0, 4, 64, 0)).size(), "and 0 is not a channel either");
    }

    @Test
    @DisplayName("the order the members arrive in does not change which blocks are joined")
    void orderDoesNotMatter() {
        List<BlockPos> forwards = at(0, 64, 0, 4, 64, 0, 9, 64, 2);
        List<BlockPos> backwards = at(9, 64, 2, 4, 64, 0, 0, 64, 0);
        assertEquals(pairs(lines(CHANNEL, forwards)), pairs(lines(CHANNEL, backwards)),
                "the server's iteration order is not a promise, and a group must not change shape with it");
    }

    @Test
    @DisplayName("the endpoints are the block centres, because a line runs from one torch to the other")
    void endpointsAreTheBlockCentres() {
        List<SyncGroupLines.Segment> segments = lines(CHANNEL, at(0, 64, 0, 1, 65, 2));
        assertEquals(1, segments.size());
        assertEquals(Set.of("0.5,64.5,0.5 | 1.5,65.5,2.5"), pairs(segments),
                "a corner would put every line a half block off its own torch");
    }

    @Test
    @DisplayName("a member in an unloaded chunk is skipped, and the members left still connect")
    void anUnloadedMemberIsSkipped() {
        BlockPos here = new BlockPos(0, 64, 0);
        BlockPos near = new BlockPos(4, 64, 0);
        BlockPos far = new BlockPos(4000, 64, 0);
        List<SyncGroupLines.Segment> segments = SyncGroupLines.lines(CHANNEL, List.of(here, near, far),
                pos -> !pos.equals(far));
        assertEquals(Set.of("0.5,64.5,0.5 | 4.5,64.5,0.5"), pairs(segments),
                "the two members this client can read are still joined, and the far one is in no pair at all - "
                        + "reading a chunk that is not loaded is how a line ends a frame with a crash");

        assertEquals(0, SyncGroupLines.lines(CHANNEL, List.of(far), pos -> false).size(),
                "and one readable member is still no pair");
    }

    @Test
    @DisplayName("a position reported twice is one torch, not a line of zero length")
    void aRepeatedPositionIsOneTorch() {
        List<BlockPos> members = at(0, 64, 0, 0, 64, 0, 4, 64, 0);
        List<SyncGroupLines.Segment> segments = lines(CHANNEL, members);
        assertEquals(1, segments.size(), "two reports of one block plus one other block are one pair");
        assertTrue(segments.get(0).x1() != segments.get(0).x2()
                        || segments.get(0).y1() != segments.get(0).y2()
                        || segments.get(0).z1() != segments.get(0).z2(),
                "and the pair that survives has two different ends, so nothing draws a point where a line belongs");
    }

    // ------------------------------------------------ 被机器搬着的成员 --

    private static final UUID MACHINE_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    /** 一台机器里的一格: 世界中心由那位姿算, 位姿栈里的坐标由同一个 frame 给出。 */
    private static SyncGroupLines.Spot inMachine(CarrierId machine, CarrierPose pose, int x, int y, int z) {
        return SyncGroupLines.Spot.carried(machine, CarrierFrame.forBlock(pose, new Vector3d(x, y, z)));
    }

    private static SyncGroupLines.Link only(List<SyncGroupLines.Link> links) {
        assertEquals(1, links.size(), "one pair is one line");
        return links.get(0);
    }

    /** 位姿里的四元数是 float, 所以端点按 1e-6 比: 这是"同一个点", 不是"同一个浮点数"。 */
    private static void assertSegment(SyncGroupLines.Segment expected, SyncGroupLines.Segment actual, String why) {
        assertEquals(expected.x1(), actual.x1(), 1.0E-6, why);
        assertEquals(expected.y1(), actual.y1(), 1.0E-6, why);
        assertEquals(expected.z1(), actual.z1(), 1.0E-6, why);
        assertEquals(expected.x2(), actual.x2(), 1.0E-6, why);
        assertEquals(expected.y2(), actual.y2(), 1.0E-6, why);
        assertEquals(expected.z2(), actual.z2(), 1.0E-6, why);
    }

    @Test
    @DisplayName("a standing member meets a carried one at the carried block's projected centre")
    void aCarriedMemberJoinsTheWorldAtItsProjectedCentre() {
        CarrierPose pose = CarrierPose.at(new Vector3d(100.0, 64.0, 0.0));
        SyncGroupLines.Link link = only(SyncGroupLines.links(CHANNEL, List.of(
                SyncGroupLines.Spot.inWorld(new BlockPos(0, 64, 0)),
                inMachine(CarrierId.subLevel(MACHINE_UUID), pose, 2, 0, 3))));
        assertNull(link.local(),
                "a cross-boundary pair is drawn in world coordinates: the two ends share no other frame");
        assertSegment(new SyncGroupLines.Segment(0.5, 64.5, 0.5, 102.0, 64.0, 3.0), link.world(),
                "the standing end is its block centre, the carried end is where that machine's pose puts the block - "
                        + "the same number the machine itself is drawn at");
    }

    @Test
    @DisplayName("two members of one machine are joined inside that machine's frame, not in world coordinates")
    void aPairInsideOneMachineGoesThroughThePoseStack() {
        // 绕 Y 转九十度: 于是"跟着位姿投影"有一个能被钉住的数 - 世界那副转了, 位姿栈里那副没转。
        CarrierPose pose = CarrierPose.of(new Vector3d(0.0, 0.0, 0.0), new Quaterniond().rotateY(Math.PI / 2.0));
        CarrierId machine = CarrierId.subLevel(MACHINE_UUID);
        SyncGroupLines.Link link = only(SyncGroupLines.links(CHANNEL, List.of(
                inMachine(machine, pose, 2, 0, 0), inMachine(machine, pose, 2, 0, 2))));
        assertNotNull(link.local(), "two members of one machine are drawn in that machine's own frame");
        assertNotNull(link.carrier(), "and the frame travels with the pair, so the pose stack is pushed with it");
        assertSegment(new SyncGroupLines.Segment(2.0, 0.0, 0.0, 2.0, 0.0, 2.0), link.local(),
                "in the frame the coordinates are the blocks' own: (local - pivot) plus half a block");
        assertSegment(new SyncGroupLines.Segment(0.0, 0.0, -2.0, 2.0, 0.0, -2.0), link.world(),
                "while the world pair is what that same pose projects them to - and a turned machine turns it");
    }

    @Test
    @DisplayName("two machines are joined in world coordinates, because one line cannot live in two frames")
    void twoMachinesAreJoinedInWorldCoordinates() {
        SyncGroupLines.Link link = only(SyncGroupLines.links(CHANNEL, List.of(
                inMachine(CarrierId.subLevel(MACHINE_UUID), CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)), 0, 0, 0),
                inMachine(CarrierId.subLevel(OTHER_UUID), CarrierPose.at(new Vector3d(-30.0, 70.0, 10.0)), 0, 0, 0))));
        assertNull(link.local(), "each machine has a pose of its own, so there is no common frame to draw that pair in");
        assertSegment(new SyncGroupLines.Segment(10.0, 64.0, 10.0, -30.0, 70.0, 10.0), link.world(),
                "each end is its own machine's projection of its own block");
    }

    // ---------------------------------------------- 兜底: 读不到又异常远的那一端 --

    @Test
    @DisplayName("a pair is dropped only when its end is unreadable AND absurdly far")
    void anUnreadableFarEndIsNotDrawn() {
        BlockPos here = new BlockPos(0, 64, 0);
        BlockPos neighbour = new BlockPos(4, 64, 0);
        // 一台机器自己的 plot 坐标: level 里真有一格, 而它离玩家四万格 - 名单要是漏了一个坐标, 画出来的就是这条线。
        BlockPos plot = new BlockPos(40_000, 64, 0);
        SyncGroupLines.WorldGate eyeAtHere = new SyncGroupLines.WorldGate(pos -> true, 0.5, 64.5, 0.5);
        assertEquals(1, SyncGroupLines.links(CHANNEL,
                List.of(SyncGroupLines.Spot.inWorld(here), SyncGroupLines.Spot.inWorld(neighbour)),
                eyeAtHere).size(),
                "两张区块都读得到: 一条线, 与 512 格那道门槛无关");

        assertEquals(0, SyncGroupLines.links(CHANNEL,
                List.of(SyncGroupLines.Spot.inWorld(here), SyncGroupLines.Spot.inWorld(plot)),
                new SyncGroupLines.WorldGate(pos -> !pos.equals(plot), 0.5, 64.5, 0.5)).size(),
                "那一端读不到, 而且离玩家四万格: 这一对不画 - 宁可少一条线, 也不画一条穿过整个世界的线");

        assertEquals(1, SyncGroupLines.links(CHANNEL,
                List.of(SyncGroupLines.Spot.inWorld(here), SyncGroupLines.Spot.inWorld(plot)),
                eyeAtHere).size(),
                "只是远不算: 客户端读得到它, 就说明它本来就是世界里的一个成员, 横跨基地的一组照画");

        assertEquals(1, SyncGroupLines.links(CHANNEL,
                List.of(SyncGroupLines.Spot.inWorld(here), SyncGroupLines.Spot.inWorld(neighbour)),
                new SyncGroupLines.WorldGate(pos -> pos.equals(here), 0.5, 64.5, 0.5)).size(),
                "只是读不到也不算: 近的那一端玩家自己看得见, 砍掉它就是把玩家身边的线拿走了");
    }

    @Test
    @DisplayName("the same local coordinate reported twice is one member of that machine")
    void aRepeatedCarriedMemberIsOne() {
        CarrierPose pose = CarrierPose.at(new Vector3d(0.0, 0.0, 0.0));
        CarrierId machine = CarrierId.subLevel(MACHINE_UUID);
        assertEquals(1, SyncGroupLines.links(CHANNEL, List.of(
                inMachine(machine, pose, 1, 0, 0), inMachine(machine, pose, 1, 0, 0),
                inMachine(machine, pose, 3, 0, 0))).size(),
                "two reports of one local coordinate plus one other block are one pair");
    }

    @Test
    @DisplayName("a group nobody can see is not drawn at all, and one visible member keeps it")
    void anOccludedGroupDrawsNothing() {
        List<SyncGroupLines.Spot> members = List.of(
                SyncGroupLines.Spot.inWorld(new BlockPos(1, 64, 1)),
                SyncGroupLines.Spot.inWorld(new BlockPos(9, 64, 9)));
        assertTrue(!SyncGroupLines.anyVisible(members, (x, y, z) -> false),
                "整组都在墙后面: 一条都不画 - 画出来像是在墙上开了一个洞, 看不出谁连给谁");
        assertTrue(SyncGroupLines.anyVisible(members, (x, y, z) -> x > 5.0),
                "只要有一个露着, 整组照画: 通向被挡住那几支的线正是\"看见一支, 知道它连着谁\"");
        assertTrue(SyncGroupLines.anyVisible(members, SyncGroupLines.Visible.ALL),
                "问不到玩家的那一帧什么都不砍");
        assertTrue(!SyncGroupLines.anyVisible(List.of(), SyncGroupLines.Visible.ALL),
                "没有成员就没有可画的东西");
    }
}

package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>同一频道的成员之间不许互相当输入.</b>
 *
 * <p>那条 {@code side=west drawn=-117,-60,-16=15 structure=20481031,128,20491272=15 answer=15
 * inputs=0x10 written=false}: 一支被搬着的同步火把与地上那支<b>同频道</b>的火把挨着, 各自把对方读成输入, 于是 OR 永远
 * 成立、答案固定 15、写回不再变化。组内部的输出对组不是消息 - 判据是 {@link SyncGroups#sameGroupNeighbour}, 一处, 六面
 * 共用, 而这里钉住它的每一种位置组合:
 *
 * <ul>
 *   <li><b>世界↔世界</b>: 邻居那个坐标对世界段成员的世界坐标 (两支立着的);</li>
 *   <li><b>世界↔被搬着的那一支</b>: {@code drawn} 那一格对世界段成员的世界坐标 - 机器把邻居画到地上那支所在的格子上;</li>
 *   <li><b>同载体内部</b>: 机器里那一格对<b>同一台载体</b>上的搬运成员 - 两台不同载体的 {@code local} 是两个坐标系。</li>
 * </ul>
 *
 * <p>而且它<b>只许吞掉同组的那一面</b>: 不是成员的邻居照旧算输入, 不同频道的两支挨着照旧互为输入。纯值那几条不需要
 * {@code ServerLevel} - 判据只吃两个已经算好的位置, 于是"同组不算输入"能在没有游戏的地方钉住。
 */
class SyncGroupsSameGroupTest {

    /** 世界段的一个成员。 */
    private static final BlockPos TORCH = new BlockPos(10, 64, 10);
    /** 它东边那一格: 另一支成员 (或者一根粉) 站的地方。 */
    private static final BlockPos EAST = TORCH.east();
    /** 它西边那一格: 空的, 那一面照旧要问。 */
    private static final BlockPos WEST = TORCH.west();
    /** 一台 Create contraption: 它的 local 相对锚点, 那里在 level 里是别人的一格。 */
    private static final CarrierId MACHINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000011001"));
    /** 第二台机器: 同一个 local 数字在它里面是<b>另一个</b>地方。 */
    private static final CarrierId OTHER_MACHINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000011002"));

    private static final BlockPos LOCAL = new BlockPos(4, 1, -3);
    private static final BlockPos LOCAL_EAST = LOCAL.east();
    /** 机器把那一面画到很远的地方: 那里没有任何成员, 于是"不算输入"只能来自另一条比对。 */
    private static final BlockPos FAR_AWAY = new BlockPos(500, 70, 500);

    private static SyncGroups.Spot standing(BlockPos pos) {
        return SyncGroups.Spot.standing(pos);
    }

    private static SyncGroups.Spot carried(CarrierId carrier, BlockPos local) {
        return SyncGroups.Spot.carried(carrier, local);
    }

    // --------------------------------------------------------- 1. 三对位置 --

    @Test
    @DisplayName("两支世界成员挨着: 那一面读到的就是同组的另一位, 两个方向都不算输入")
    void twoWorldMembersDoNotFeedEachOther() {
        SyncGroups.GroupMembers group = SyncGroups.GroupMembers.of(List.of(standing(TORCH), standing(EAST)));

        assertTrue(SyncGroups.sameGroupNeighbour(group, standing(TORCH), EAST, EAST),
                "立着的那一支的 drawn 就是它自己的邻居 (位姿为空), 于是世界↔世界这一对走的就是同一句");
        assertTrue(SyncGroups.sameGroupNeighbour(group, standing(EAST), TORCH, TORCH),
                "反方向同样成立: 一组的 OR 里没有'上游', 谁读谁都是自己");
        assertFalse(SyncGroups.sameGroupNeighbour(group, standing(TORCH), WEST, WEST),
                "西边那一格没有成员: 那一面照旧要问");
    }

    @Test
    @DisplayName("被搬着的那一支把邻居画到地上那支所在的格子: drawn 与世界段的坐标对上, 不算输入")
    void theDrawnCellOfACarriedMemberIsComparedWithTheWorldMembers() {
        SyncGroups.GroupMembers group =
                SyncGroups.GroupMembers.of(List.of(standing(TORCH), carried(MACHINE, LOCAL)));

        assertTrue(SyncGroups.sameGroupNeighbour(group, carried(MACHINE, LOCAL), TORCH, LOCAL.west()),
                "那条日志: 成员把 west 面画在 -117,-60,-16, 而地上那支就站在那一格 - 它的 15 是同一组的答案");
        assertFalse(SyncGroups.sameGroupNeighbour(group, carried(MACHINE, LOCAL), TORCH.above(), LOCAL.west()),
                "画到别处就不是同组的另一位: 那边要是有根粉, 它照旧算输入");
    }

    @Test
    @DisplayName("同一台机器里的两支: 机器里那一格对上同一个坐标系里的另一位, 不算输入")
    void aNeighbourInsideTheSameMachineIsAnotherMember() {
        SyncGroups.GroupMembers group =
                SyncGroups.GroupMembers.of(List.of(carried(MACHINE, LOCAL), carried(MACHINE, LOCAL_EAST)));

        assertTrue(SyncGroups.sameGroupNeighbour(group, carried(MACHINE, LOCAL), FAR_AWAY, LOCAL_EAST),
                "structure 那一份比的是 local 坐标: 那是同一台机器的坐标系, 于是这一条与画在哪无关");
    }

    @Test
    @DisplayName("两台不同载体的 local 是两个坐标系: 同一个数字不是同一个地方, 那一条比对不成立")
    void theSameLocalNumberOnAnotherCarrierIsSomewhereElse() {
        SyncGroups.GroupMembers group =
                SyncGroups.GroupMembers.of(List.of(carried(MACHINE, LOCAL), carried(OTHER_MACHINE, LOCAL_EAST)));

        assertFalse(SyncGroups.sameGroupNeighbour(group, carried(MACHINE, LOCAL), FAR_AWAY, LOCAL_EAST),
                "两台不同载体之间只有'各自画在哪'可比: 拿另一个坐标系里的数字去比会吞掉真正的输入");
    }

    // --------------------------------------- 2. 不该被吞掉的那些邻居 --

    @Test
    @DisplayName("不是成员的邻居照旧算输入: 飞机掠过地上的粉、隔壁那支别的频道的火把, 一个字节都没变")
    void aNeighbourThatIsNotAMemberStillCounts() {
        SyncGroups.GroupMembers alone = SyncGroups.GroupMembers.of(List.of(standing(TORCH)));

        assertFalse(SyncGroups.sameGroupNeighbour(alone, standing(TORCH), EAST, EAST),
                "组里只有它自己: 东边那一格不管是粉还是方块, 照旧要问");
        assertFalse(SyncGroups.sameGroupNeighbour(SyncGroups.GroupMembers.ALONE, standing(TORCH), EAST, EAST),
                "自成一组的成员没有'别人': 挨着的两支未绑定火把照旧互为输入 - 本判据不改这条");
    }

    @Test
    @DisplayName("不同频道的两支挨着: 它们不是一组, 两队都不许跳过; 同一对位置改绑到同一个频道才算")
    void anotherChannelIsStillAnInput() {
        SyncChannels channels = new SyncChannels();
        channels.put(TORCH, 7);
        channels.put(EAST, 8);
        SyncGroups.Roster roster = SyncGroups.roster(channels);
        SyncGroups.GroupMembers seven = SyncGroups.GroupMembers.of(roster.membersOf(7));
        SyncGroups.GroupMembers eight = SyncGroups.GroupMembers.of(roster.membersOf(8));

        assertFalse(SyncGroups.sameGroupNeighbour(seven, standing(TORCH), EAST, EAST),
                "8 号那支不是 7 号这一组的成员: 7 号照旧听得见它");
        assertFalse(SyncGroups.sameGroupNeighbour(eight, standing(EAST), TORCH, TORCH),
                "从 8 号那一侧看也一样: 两支各有各的组, 于是那一面照旧算输入");

        channels.put(EAST, 7);
        SyncGroups.GroupMembers same = SyncGroups.GroupMembers.of(SyncGroups.roster(channels).membersOf(7));
        assertTrue(SyncGroups.sameGroupNeighbour(same, standing(TORCH), EAST, EAST),
                "同一对位置、同一个频道: 这一刻才是同组的另一位");
    }

    // ------------------------------------------------- 3. 判据只有一处 --

    @Test
    @DisplayName("判据只写一处, 六面共用一次调用, 而且在吸附面分岔之前")
    void thereIsExactlyOneCriterionAndEveryFaceGoesThroughIt() throws IOException {
        String groups = main("logic", "SyncGroups.java");

        String criterion = bodyOf(groups, "static boolean sameGroupNeighbour(");
        assertTrue(criterion.contains("holdsWorld(drawn)"),
                "第一条: 邻居画在世界上的那一格对上世界段成员的坐标");
        assertTrue(criterion.contains("holdsInside(spot.carrier(), inStructure)"),
                "第二条: 机器里的那一格只与同一台载体上的成员比");
        // 三处而不是两处: 走线那条新判据 (fedByGroupOnly / WireWalk) 判断"这一格是不是本组成员"时**复用同一句**,
        // 而不是另写一份 —— 两处调用 + 一处声明, 正是"判据只有一处"的样子。
        assertEquals(3, occurrences(groups, "holdsWorld("), "一处声明 + 两处调用 (六面 / 走线), 别处不许再比一遍");
        assertEquals(3, occurrences(groups, "holdsInside("), "同上: 两个坐标系不许在调用点重新混起来");
        // 这条判据本身是"某一面读到的是不是本组成员", 只有六面会问; 走线那条问的是"这一格是不是本组成员",
        // 于是它复用的是下面那两个 accessor, 而不是这个"按面"的包装 —— 一处声明 + 那一处调用。
        assertEquals(2, occurrences(groups, "sameGroupNeighbour("),
                "一处声明 + 六面那一处调用: 判据不许被抄成第二份");

        String askAt = bodyOf(groups, "private static int askAt(");
        assertEquals(1, occurrences(askAt, "sameGroupNeighbour("), "六面里每面都问, 而问的是同一处");
        assertTrue(askAt.indexOf("sameGroupNeighbour(") < askAt.indexOf("if (side == support)"),
                "而且判据在吸附面/其余五面分岔<b>之前</b>: 吸附面也是六面之一");
        assertFalse(askAt.contains("holdsWorld("), "比对的实现不许在调用点再写一遍");
        assertFalse(askAt.contains("holdsInside("), "同上");
        assertTrue(askAt.contains("trace.faceSkipped(member, side, asked, SAME_GROUP)"),
                "被跳过的那一面要留一行, 理由就是同一个常量");

        String runPass = bodyOf(groups, "private static boolean runPass(");
        assertEquals(1, occurrences(runPass, "sameGroups("), "一轮建一次名单, 六面里每面再收一次表是不可能的写法");
        assertTrue(runPass.indexOf("sameGroups(") < runPass.indexOf("askAt("), "名单在问面之前建好");
        assertTrue(bodyOf(groups, "private static Map<Spot, GroupMembers> sameGroups(")
                        .contains("GroupMembers.ALONE"),
                "自成一组的成员在这份名单里没有别人");
    }

    // ------------------------------------------------------- 4. trace 理由 --

    @Test
    @DisplayName("trace 里那一面的理由是 same-group, 而且走的还是 faceSkipped 那条通道")
    void theReasonIsWrittenIntoTheTrace() throws IOException {
        assertEquals("same-group", SyncGroups.SAME_GROUP, "复现时 grep 的就是这个词");

        String groups = main("logic", "SyncGroups.java");
        String trace = bodyOf(groups, "static final class GroupTrace");
        assertTrue(trace.contains("skipped=\" + reason"),
                "理由照旧从 faceSkipped 写出去: 新理由不许自己另开一条日志");
        assertTrue(bodyOf(groups, "static boolean sameGroupNeighbour(").contains("holdsWorld(drawn)"),
                "同一句判据");
    }

    // ------------------------------------------------------------- helpers --

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

    private static int occurrences(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }
}

package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierAccess;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>一台载体里那一格的另外五面: 两份存储, "机器自己那一格"是哪一格由载体形态决定.</b>
 *
 * <p>吸附面一直是两侧都读的 ({@code ProductFrame#signalInto}), 另外五面只问"画出来的那一格" - 于是机器<b>内部</b>贴在
 * 火把侧面的那根粉读不到。判据只有一条 ({@link SyncGroups#ownWorldOf} → {@code CarrierStore#storageViewOf}),
 * 而这里钉住它的三个方向:
 *
 * <ul>
 *   <li><b>Sable 子层级</b>: 它的 {@code local} <b>就是</b> level 坐标, 于是"机器自己那一格"就是 level 的
 *       {@code neighbour} - 交回来的那一份存储<b>就是 level 本身</b>, 读的坐标是那个 plot 坐标, 而不是这台机器被画
 *       出来的世界位置 (那里是空气)。上一版对这一支答 {@code null}, 于是侧面输入一次都没被读到;</li>
 *   <li><b>Create contraption</b>: 交回来的是它自己的方块视图, 问的仍然是那个 local 坐标, level 一次都不问;</li>
 *   <li>立在世界里的那一支、以及没有物理后端时的 contraption: {@code null}, 也就是"没有第二份"。</li>
 * </ul>
 *
 * <p>纯值那几条不需要真方块: 单元测试没有方块注册表 ({@code Blocks.AIR.defaultBlockState()} 会先炸), 于是视图答
 * {@code null} - 而"问的是哪个坐标"由假视图自己记下来, 这正是要钉的那句话。
 */
class SyncGroupsOwnWorldReadTest {

    /** 一台 Create contraption: 它的 local 相对锚点, 那里在 level 里是别人的一格。 */
    private static final CarrierId MACHINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000009601"));
    /** 一台 Sable 子层级: 它的 local 就是 level 坐标。 */
    private static final CarrierId SUB_LEVEL =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000009602"));

    private static final BlockPos LOCAL = new BlockPos(4, 1, -3);
    private static final BlockPos WORLD = new BlockPos(120, 64, -40);

    @Nullable
    private CarrierStore.CarrierSource previous;

    @BeforeEach
    void rememberTheInstalledSource() {
        previous = CarrierStore.source();
    }

    @AfterEach
    void putItBack() {
        CarrierStore.install(previous);
    }

    /** 一份方块存储, 记下每一次被问到的坐标。没有方块注册表, 于是它答 {@code null} - 也就是"这一格读不到"。 */
    private static final class FakeView implements BlockGetter {

        private final List<BlockPos> reads = new ArrayList<>();

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Nullable
        @Override
        public BlockState getBlockState(BlockPos pos) {
            this.reads.add(pos);
            return null;
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 384;
        }

        @Override
        public int getMinBuildHeight() {
            return -64;
        }
    }

    /** The compat half, as far as this question can tell: one block view, and a count of how often it was asked. */
    private static final class FakeSource implements CarrierStore.CarrierSource {

        @Nullable
        private final CarrierAccess access;

        private int accessCalls;

        FakeSource(@Nullable CarrierAccess access) {
            this.access = access;
        }

        @Nullable
        @Override
        public CarrierPose poseOf(ServerLevel level, CarrierId carrier) {
            return null;
        }

        @Nullable
        @Override
        public CarrierId carrierAt(ServerLevel level, BlockPos worldPos) {
            return null;
        }

        @Nullable
        @Override
        public CarrierAccess access() {
            this.accessCalls++;
            return this.access;
        }

        @Override
        public String describe() {
            return "unit-test source";
        }
    }

    // ------------------------------------------- 1. contraption 那一支: 性状不变 --

    @Test
    @DisplayName("contaption 的一格: 五面多问的是它自己的方块视图, 问的就是那个 local 坐标")
    void theContraptionGetsItsOwnWorld() {
        FakeView level = new FakeView();
        FakeView view = new FakeView();
        FakeSource source = new FakeSource(carrier -> view);
        CarrierStore.install(source);

        BlockGetter own = SyncGroups.ownWorldOf(level, SyncGroups.Spot.carried(MACHINE, LOCAL));

        assertNotNull(own, "存储不在 level 上的载体, 五面要多问它自己那一份: 它的邻居只活在它自己的 world 里");
        assertSame(view, own, "问的就是载体层那一份视图, 不是第二份实现");
        assertEquals(1, source.accessCalls, "一趟一个成员只问一次");
        assertTrue(level.reads.isEmpty(), "而且 level 一次都没被问: 拿 local 去问 level 只会问到别人的空气");

        assertEquals(0, SyncGroups.askInStructure(own, LOCAL, Direction.EAST),
                "那一格读不到 (视图答 null) 就答 0 - 读不到绝不被当成信号");
        assertEquals(List.of(LOCAL), view.reads, "机器里的邻居: 问的是 local 坐标本身");
    }

    @Test
    @DisplayName("载体读不出来时答'没有那一份', 绝不退回去读 level")
    void anUnreadableViewAddsNothing() {
        CarrierStore.install(new FakeSource(carrier -> null));

        assertNull(SyncGroups.ownWorldOf(new FakeView(), SyncGroups.Spot.carried(MACHINE, LOCAL)),
                "没有视图就是没有这一份: 拿 local 去问 level 只会问到别人的空气");
    }

    // ------------------------------------------- 2. Sable 那一支: 现在两侧都读 --

    /** 一台机器的位姿: 它的局部坐标因此被画到很远的地方 - 与 plot 里那一格不是同一个位置。 */
    private static CarrierPose parkedFarAway() {
        return CarrierPose.at(new Vector3d(500.0D, 70.0D, 500.0D));
    }

    @Test
    @DisplayName("子层级那一格: 交回来的那一份存储就是 level 本身, 于是那个 plot 邻居现在读得到了")
    void theSubLevelReadsTheLevelItself() {
        FakeView level = new FakeView();
        FakeSource source = new FakeSource(carrier -> new FakeView());
        CarrierStore.install(source);

        BlockGetter own = SyncGroups.ownWorldOf(level, SyncGroups.Spot.carried(SUB_LEVEL, LOCAL));

        assertSame(level, own, "存储就是 level 的那一支: 机器自己那一格就是 level 的 plot 坐标那一格");
        assertEquals(0, source.accessCalls, "而且载体视图一次都没被问 - 那是 contraption 那一支的路");
    }

    @Test
    @DisplayName("画出来的那一格与会读的那一格是两个位置: 侧面那根粉只在前者之外的那个坐标上")
    void theStructureReadAsksThePlotCoordinateNotTheDrawnOne() {
        FakeView level = new FakeView();
        CarrierStore.install(new FakeSource(carrier -> new FakeView()));
        BlockPos neighbour = LOCAL.relative(Direction.EAST);

        ProductFrame frame = ProductFrame.forHost(SUB_LEVEL, parkedFarAway());
        BlockPos drawn = frame.worldPosOf(neighbour);
        assertFalse(drawn.equals(neighbour),
                "这台机器被画在很远的地方: 画出来的那一格与 plot 里那一格本来就不是同一个坐标");

        BlockGetter own = SyncGroups.ownWorldOf(level, SyncGroups.Spot.carried(SUB_LEVEL, LOCAL));
        assertNotNull(own, "子层级现在也有第二份读数");
        SyncGroups.askInStructure(own, neighbour, Direction.EAST);

        assertEquals(List.of(neighbour), level.reads,
                "读的是 neighbour 这个 plot 坐标 - 贴在火把侧面的那根粉就在这里, 而 drawn 那一格是空气");
        assertFalse(level.reads.contains(drawn), "于是这一面不再是'只读 drawn'");
    }

    // --------------------------------------------------- 3. 没有第二份的那几种 --

    @Test
    @DisplayName("立在世界里的那一支永远是'没有第二份', 即使物理后端在")
    void aStandingMemberNeverGetsAnOwnWorld() {
        FakeSource source = new FakeSource(carrier -> new FakeView());
        CarrierStore.install(source);

        assertNull(SyncGroups.ownWorldOf(new FakeView(), SyncGroups.Spot.standing(WORLD)),
                "立在世界里的那一支没有载体: level 就是它自己的答案");
        assertEquals(0, source.accessCalls);
    }

    @Test
    @DisplayName("没有物理后端时没有载体视图; 立在世界里的那一支照样什么都没有")
    void withoutABackendNothingIsAsked() {
        CarrierStore.install(null);

        assertNull(SyncGroups.ownWorldOf(new FakeView(), SyncGroups.Spot.carried(MACHINE, LOCAL)),
                "没有后端就没有 contraption 的视图");
        assertNull(SyncGroups.ownWorldOf(new FakeView(), SyncGroups.Spot.standing(WORLD)));
    }

    // ------------------------------------------------------- 4. source wiring --

    @Test
    @DisplayName("'哪一份存储'只有一处判据, 在载体层; 组 pass 里一处也没有")
    void thereIsExactlyOneCriterionAndItSitsInTheCarrierLayer() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String store = main("carrier", "CarrierStore.java");

        assertEquals(1, occurrences(groups, "CarrierStore.storageViewOf("),
                "'哪一份存储'在组 pass 里只问一次");
        assertEquals(0, occurrences(groups, "CarrierStore.storageInLevel("),
                "两种载体的判据不许在组 pass 里再写一遍: 它在载体层只有一处");
        assertEquals(0, occurrences(groups, "CarrierStore.blockViewOf("),
                "载体视图也只从载体层那一个入口取, 没有第二份实现");

        String own = bodyOf(groups, "static BlockGetter ownWorldOf(");
        assertTrue(own.contains("CarrierStore.storageViewOf(level, spot.carrier())"),
                "两件事都在 ownWorldOf 里: 判据一处, 存储一份");

        String criterion = bodyOf(store, "public static BlockGetter storageViewOf(");
        assertTrue(criterion.contains("storageInLevel(carrier) ? level : blockViewOf(carrier)"),
                "而那一条判据答的是'哪一份': 存储就是 level 的子层级交回 level 本身, 其余交回它自己的视图");

        String askAt = bodyOf(groups, "private static int askAt(");
        assertEquals(1, occurrences(askAt, "ownWorldOf(level, spot)"),
                "一趟一个成员问一次: 六面里每面再判一次就会给出六个'这台是哪一种载体'");
        assertTrue(askAt.indexOf("ownWorldOf(level, spot)") < askAt.indexOf("for (Direction side : Direction.values())"),
                "而且是在循环之前算好的 - 判据不许在循环里散开");
        assertFalse(askAt.contains("isSubLevel"), "组 pass 不许自己判'这台载体是哪种'");
        assertFalse(askAt.contains("isContraption"), "同上");
        assertFalse(askAt.contains("getContraptionWorld"), "主源集也不许出现 Create 的类型与调用");
    }

    @Test
    @DisplayName("五面的那一次读物: 画出来的那一格与机器自己那一份取较大的一个, 而且只读一次")
    void theFiveFacesAskBothWorlds() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        // 语义一个字未改, 于是这道守卫跟着代码走, 而不是跟着文件名走。
        String askSide = bodyOf(groups, "private static int askSide(");

        assertTrue(askSide.contains("int structure = ownWorld == null ? -1 : askInStructure(ownWorld, neighbour, side)"),
                "机器自己那一份问的是 neighbour (local 坐标), 不是画出来的那一格; -1 只给诊断区分'没有这一份'");
        assertTrue(askSide.contains("int answer = ownWorld == null ? drawn : Math.max(drawn, structure)"),
                "取较大的一个: 一个邻居最多只在这两份里的一份中 - 现有的'取大'形状一个字未改");
        assertEquals(1, occurrences(askSide, "askInStructure("),
                "一面只读一次机器自己那一份: 为了 trace 再读一遍就是两个答案");
    }

    @Test
    @DisplayName("Sable 那一条的问法逐字未变: 五面仍然先问画出来的那一格, 吸附面仍然走 signalInto")
    void theDrawnReadIsByteForByteTheSame() throws IOException {
        String groups = main("logic", "SyncGroups.java");
        String askAt = bodyOf(groups, "private static int askAt(");
        String askSide = bodyOf(groups, "private static int askSide(");

        assertTrue(askSide.contains("SyncTorchRule.ask(level.getBlockState(asked).isRedstoneConductor(level, asked),"),
                "五面原来那一句一个字都没改: 导体那条判据照旧 (这一句随五面那一支搬进了 askSide)");
        assertTrue(askSide.contains("level.getSignal(asked, frame.worldFace(side))")
                        && askSide.contains("level.getDirectSignal(asked, frame.worldFace(side))"),
                "问的仍然是画出来的那一格与转过去的那一面");
        assertTrue(askAt.contains("answer = PowerSolver.clamp(frame.signalInto(level, neighbour, side));"),
                "吸附面照旧走 ProductFrame#signalInto, 那是本 mod 对'邻居给了这一格多少'的唯一答案");
        assertTrue(askAt.contains("if (side == support) {"),
                "两个方向仍然分开写: 吸附面与新多问的那一份不是同一句话");
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

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
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>结构里的一台活塞搬走一格方块: 机器按 (机器, 机器内坐标) 记的东西一起搬。</b>
 *
 * <p>要钉的那一幕: 结构里那台活塞推/收一次之后, 打掉<b>新位置</b>那块方块, 眼镜那条线还在。根因不是名单闸门没问,
 * 而是它<b>没机会问</b> —— 搬运索引里那条记录仍停在旧机器内坐标上, 而 {@code hitAt} 只认键恰好等于那一格的记录, 于是
 * {@code destroyedHostAt} 答 {@code null}, 连 {@code discard} 都不发生。
 *
 * <p>本类按三层钉住: 一次搬动的纯值 (新键查得到 / 旧键查不到 / 目标有人就不覆盖 / 没有机器时零变化)、搬完之后那次破坏
 * 能不能走到 {@code discard} 与名单, 以及那一句到底接在活塞的哪一次调用里。
 */
class PistonCarriedMoveTest {

    /** 一台子层级。机器内坐标就是 level 坐标, 所以下面那几格同时是世界里的坐标。 */
    private static final CarrierId MACHINE =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000005001"));

    /** plot 里的三格, 一格比一格靠东。 */
    private static final BlockPos A = new BlockPos(20481032, 64, 20481036);
    private static final BlockPos B = A.east();
    private static final BlockPos C = B.east();

    /** 它离开世界时的那一格 —— 与机器内坐标无关, 结构飞多远都不变。 */
    private static final BlockPos ORIGIN_A = new BlockPos(17, 64, -20);
    private static final BlockPos ORIGIN_B = ORIGIN_A.east();

    /** 结构此刻在世界里画在哪 (与记录里的原文坐标无关, 只用于那条被毁的链)。 */
    private static final BlockPos DRAWN = new BlockPos(-117, -60, -16);

    /** The world store, as a map - the same stand-in every carrier test uses. */
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

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    @AfterEach
    void clearRows() {
        // 频道那两张表是整个进程的静态表 (它们不进存档), 于是每个用例自己收尾。
        CarriedSyncTorches.clear();
    }

    /** 认领一颗元件: 机器内坐标 {@code local}, 它离开世界时站在 {@code origin}。 */
    private CarriedHost claim(CarrierId carrier, BlockPos local, BlockPos origin, ComponentType type) {
        world.set(origin, new Slot(type));
        index.claim(carrier, local, origin, world);
        return index.at(carrier, local);
    }

    // ------------------------------------------------------------ 纯值: 搬动 --

    @Test
    @DisplayName("纯值：搬完之后记录在新机器内坐标上查得到、旧的那一格查不到")
    void aMovedBlockKeepsItsRecordAtTheNewLocal() {
        CarriedHost before = claim(MACHINE, A, ORIGIN_A, ComponentType.SYNC_TORCH);
        assertNotNull(before, "这一格里有一颗内嵌元件, 它按 (机器, 机器内坐标) 记着");

        int moved = CarrierStore.moveWithinCarriers(index, List.of(A), Direction.EAST, pos -> MACHINE);

        assertEquals(1, moved, "一条记录搬一次");
        assertNull(index.at(MACHINE, A), "旧那一格上已经没有记录了");
        CarriedHost after = index.at(MACHINE, B);
        assertNotNull(after, "新那一格上查得到它在, 于是按位置问记录的路都找得到它");
        assertEquals(ComponentType.SYNC_TORCH, after.slot().type, "搬的是同一条记录, 不是一条新的");
        assertEquals(ORIGIN_A, after.origin(), "origin 是它离开世界时那一格, 不跟着机器内坐标走");
        assertEquals(before.at(), after.at(), "它此刻在世界里作答的那一格也没变");
        assertEquals(1, index.liveHostsOf(MACHINE).size(), "一条记录搬一次, 不是两条");
    }

    /**
     * 一排方块里一格既是一个的落点又是另一个的起点。两段式 (旧键先全部收走, 新键再一起写) 就是为了这一格:
     * 边读边写会把一条记录写到还没离开的那一条上面。
     */
    @Test
    @DisplayName("纯值：一排方块同时搬的时候两条记录都不丢")
    void aRowMovesWithoutLosingEitherRecord() {
        claim(MACHINE, A, ORIGIN_A, ComponentType.DUST);
        claim(MACHINE, B, ORIGIN_B, ComponentType.REPEATER);

        int moved = CarrierStore.moveWithinCarriers(index, List.of(A, B), Direction.EAST, pos -> MACHINE);

        assertEquals(2, moved, "两条都搬了");
        assertNull(index.at(MACHINE, A), "整排的尾巴空了");
        assertEquals(ComponentType.DUST, index.at(MACHINE, B).slot().type, "后面那一块到了原来站在它前面的那一格");
        assertEquals(ComponentType.REPEATER, index.at(MACHINE, C).slot().type, "前面的那一块到了再前面一格");
        assertEquals(2, index.liveHostsOf(MACHINE).size(), "还是两条");
    }

    @Test
    @DisplayName("纯值：目标格上已经有一条记录时不覆盖 —— 那一条留下，这一条留在旧格")
    void anOccupiedTargetKeepsBothRecords() {
        claim(MACHINE, A, ORIGIN_A, ComponentType.DUST);
        claim(MACHINE, B, ORIGIN_B, ComponentType.REPEATER);

        // 只搬 A: B 上那一条不是这一批里要走的, 于是它是占用者。
        int moved = CarrierStore.moveWithinCarriers(index, List.of(A), Direction.EAST, pos -> MACHINE);

        assertEquals(0, moved, "一条都没搬");
        assertEquals(ComponentType.DUST, index.at(MACHINE, A).slot().type,
                "被推的那一条留在旧格 —— 与搬运世界 store 时「两个就都要留着」是同一句话");
        assertEquals(ComponentType.REPEATER, index.at(MACHINE, B).slot().type, "目标格那一条一个字都没变");
        assertEquals(2, index.liveHostsOf(MACHINE).size(), "两条都还在");
    }

    /**
     * 已落地的记录按它自己的 {@code at()} 在世界里作答, 机器内坐标只是它身份的一半: 搬它只会给一条已经交出去的记录
     * 编一个新地址。
     */
    @Test
    @DisplayName("纯值：已经落地的记录不搬")
    void aLandedRecordIsNotMoved() {
        claim(MACHINE, A, ORIGIN_A, ComponentType.DUST);
        index.release(MACHINE, A, null, world);
        assertTrue(index.at(MACHINE, A).landed(), "它已经交回世界了");

        int moved = CarrierStore.moveWithinCarriers(index, List.of(A), Direction.EAST, pos -> MACHINE);

        assertEquals(0, moved, "落地记录不是这一批要搬的东西");
        assertNull(index.at(MACHINE, B), "于是新那一格上什么都没有");
    }

    @Test
    @DisplayName("纯值：这一格不属于任何机器时零变化")
    void nothingHappensWithoutACarrier() {
        claim(MACHINE, A, ORIGIN_A, ComponentType.SYNC_TORCH);
        CarriedSyncTorches.capture(MACHINE, A, ORIGIN_A, 7, (byte) 0x10, true);

        int moved = CarrierStore.moveWithinCarriers(index, List.of(A), Direction.EAST, pos -> null);

        assertEquals(0, moved, "答不出归属就不是我的格子");
        assertNotNull(index.at(MACHINE, A), "索引里一个字都没动");
        assertNull(index.at(MACHINE, B));
        assertNotNull(CarriedSyncTorches.at(MACHINE, A), "频道那一行也一格没动");
        assertNull(CarriedSyncTorches.at(MACHINE, B));
    }

    // ------------------------------------------------- 纯值: 频道行也跟着搬 --

    @Test
    @DisplayName("纯值：机器里那一支同步火把的频道行跟着换格子 —— 号码、掩码、亮灭、离开世界的那一格都原样")
    void aCarriedSyncTorchRowTravelsWithTheBlock() {
        CarriedSyncTorches.capture(MACHINE, A, ORIGIN_A, 7, (byte) 0x10, true);

        CarrierStore.moveWithinCarriers(index, List.of(A), Direction.EAST, pos -> MACHINE);

        assertNull(CarriedSyncTorches.at(MACHINE, A), "旧格上的那一行已经走了");
        CarriedSyncTorches.Row row = CarriedSyncTorches.at(MACHINE, B);
        assertNotNull(row, "新格上还是那一支火把, 于是组 pass 照样找得到它");
        assertEquals(7, row.channel(), "号码是它的身份");
        assertEquals((byte) 0x10, row.inputs(), "这一刻的输入掩码也一起搬 (它是按 (机器, 机器内坐标) 记的)");
        assertTrue(row.lit(), "组 pass 定的答案原样");
        assertEquals(ORIGIN_A, row.origin(), "离开世界的那一格不变 —— 结构飞多远都不变");
    }

    @Test
    @DisplayName("纯值：目标格上已经有一行时不覆盖")
    void anOccupiedTargetRowIsNotOverwritten() {
        CarriedSyncTorches.capture(MACHINE, A, ORIGIN_A, 7, (byte) 0x10, true);
        CarriedSyncTorches.capture(MACHINE, B, ORIGIN_B, 9, (byte) 0x01, false);

        CarrierStore.moveWithinCarriers(index, List.of(A), Direction.EAST, pos -> MACHINE);

        assertEquals(7, CarriedSyncTorches.at(MACHINE, A).channel(), "被推的那一行留在旧格");
        assertEquals(9, CarriedSyncTorches.at(MACHINE, B).channel(), "目标格那一行没被覆盖");
    }

    // --------------------------------------------------- 纯值: 搬完之后被毁 --

    /**
     * 闭合就在这一格: 搬完之后, <b>新位置那一格</b>才是这条记录的那一格, 于是按新位置问的每一条路都答得出东西,
     * 而 {@code discard} 之后这台机器的名单里就少一个人 —— 名单闸门问的正是这一件事。
     */
    @Test
    @DisplayName("纯值：搬完之后按新位置被毁 —— 能走到 discard，于是名单闸门看得见少了一个人")
    void afterTheMoveTheNewCellIsTheOneThatCanBeDestroyed() {
        claim(MACHINE, A, ORIGIN_A, ComponentType.SYNC_TORCH);

        // 修之前的样子: 新那一格上没有记录, 于是按新位置问什么都问不到 —— 这就是「打掉新位置那块方块, 线还在」。
        assertNull(CarrierStore.hitInStructure(index, MACHINE, B, DRAWN),
                "修之前: 新位置上那条记录根本不存在");

        CarrierStore.moveWithinCarriers(index, List.of(A), Direction.EAST, pos -> MACHINE);

        CarriedBlockRemoval.Hit hit = CarrierStore.hitInStructure(index, MACHINE, B, DRAWN);
        assertNotNull(hit, "搬完之后, 新那一格就是这条记录的那一格");
        // 世界 store 那里什么都没有 (元件在机器里), 结构自己的视图说这一格没了 ⇒ 归搬运层。
        CarriedBlockRemoval.Destroyed destroyed = CarriedBlockRemoval.resolve(index, null, hit, DRAWN,
                CarriedBlockRemoval.StructureViewState.GONE,
                CarriedBlockRemoval.ViewRequirement.VIEW_MUST_SAY_GONE);
        assertNotNull(destroyed, "于是这一下破坏真的被搬运层认领");
        assertEquals(B, destroyed.localPos(), "认领的是新机器内坐标");

        assertNull(index.discard(MACHINE, A), "旧位置上已经没有能摘的东西");
        assertNotNull(index.discard(MACHINE, B), "新位置摘得到 —— 这就是名单闸门之前那一步");
        assertTrue(index.liveHostsOf(MACHINE).isEmpty(),
                "名单从 liveHostsOf 收人: 此刻它一个人都不收, 于是闸门看得见少了一个");
    }

    // ---------------------------------------------------------------- 接线 --

    /**
     * 那一句到底接在哪: 活塞搬 {@code Stored} 的同一次调用里, 而且排在「这个 level 什么都没存」的早退<b>之前</b>
     * —— 结构里的元件与频道根本不在世界 store 里, 于是那个早退对它们是一句假的空。
     */
    @Test
    @DisplayName("接线：活塞搬 Stored 的同一次调用里搬索引，且排在「这个 level 什么都没存」的早退之前")
    void thePistonMoveCarriesTheCarrierSideToo() throws IOException {
        String piston = flat(main("logic", "PistonHostTransfer.java"));
        String body = bodyOf(piston, "public static void blocksAreAboutToMove(Level level, BlockPos pistonPos, "
                + "Direction travel, List<BlockPos> toPush)");

        assertTrue(body.contains("CarrierStore.blocksMovedInStructure(serverLevel, sources, travel);"),
                "活塞这一侧只有这一句: 世界 store 的行、世界形态的频道行, 以及机器按 (机器, 机器内坐标) 记的东西"
                        + "必须在同一次调用里一起搬");
        assertTrue(offsetOf(body, "CarrierStore.blocksMovedInStructure(serverLevel, sources, travel);")
                        < offsetOf(body, "if (store.isEmpty()) {"),
                "排在早退之前: 结构里的元件与频道不在世界 store 里, 「这个 level 什么都没存」不等于「这格里没有东西」");
        assertFalse(piston.contains("plotOwnerAt"),
                "判据只许一处: 「这一格属于哪台载体」不许在活塞这一侧再问一遍");
        assertFalse(piston.contains("CarrierSource"),
                "活塞这一侧不该知道任何兼容层的形状");
    }

    @Test
    @DisplayName("接线：判据、索引搬动与频道行搬动各只有一处调用, 且两批用的是同一张 from→to 表")
    void oneCriterionAndOneMove() throws IOException {
        String store = flat(main("carrier", "CarrierStore.java"));
        String entry = bodyOf(store, "public static int blocksMovedInStructure(ServerLevel level, "
                + "List<BlockPos> sources, Direction travel)");
        assertEquals(1, count(entry, "plotOwnerOf(level, pos)"),
                "这一格属于哪台载体只问一次, 问的是点击层与结构帧读取用的同一句 plotOwnerAt");
        assertTrue(offsetOf(entry, "!isActive()") < offsetOf(entry, "moveWithinCarriers("),
                "没有载体时零变化: 那句守卫在方法的第一行, 在它之前什么都不做");

        String batch = bodyOf(store, "static int moveWithinCarriers(@Nullable CarriedHostIndex index, "
                + "@Nullable List<BlockPos> sources, @Nullable Direction travel, "
                + "@Nullable Function<BlockPos, CarrierId> ownerOf)");
        assertEquals(1, count(store, "index.moveLocals("), "索引的搬动只有这一个调用点");
        assertEquals(1, count(store, "CarriedSyncTorches.remap("), "频道行的搬动也只有这一个");
        assertTrue(batch.contains("index.moveLocals(entry.getKey(), entry.getValue())")
                        && batch.contains("CarriedSyncTorches.remap(entry.getKey(), entry.getValue())"),
                "两批用的是同一张 from→to 表: 一排方块里一格既是落点又是起点, 分开搬就不成立");
        assertTrue(batch.contains("perCarrier.computeIfAbsent(owner"),
                "一台机器一趟一张表, 而不是一格一格地先来后到");

        String indexSource = flat(main("carrier", "CarriedHostIndex.java"));
        String move = bodyOf(indexSource, "public LocalMoves moveLocals(@Nullable CarrierId carrier, "
                + "@Nullable Map<BlockPos, BlockPos> moves)");
        assertTrue(move.contains("hosts.containsKey(to.asLong()) && !leaving.containsKey(to)"),
                "去重与既有的 from→to 同一句: 目标格上有一条不走的东西就谁的都不覆盖");
        assertTrue(move.contains("if (host == null || host.landed()) {"),
                "已落地的记录不搬");
    }

    // --------------------------------------------------------------- 工具 --

    private static int count(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }

    private static String main(String area, String name) throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", area, name);
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
        assertTrue(offset >= 0, "判据的锚点 '" + anchor + "' 已经不在源码里了");
        return offset;
    }

    /** 去掉排版差异的源码, 于是子串判据不依赖换行与缩进。 */
    private static String flat(String source) {
        return source.replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}

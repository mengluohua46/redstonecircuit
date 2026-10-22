package com.jiangyuefengyu.redstonecircuit.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * The one place a blueprint's frame becomes the world's, before a single block of it exists.
 *
 * <p>Rotation and mirror are not in the blueprint file: they are item components on the stack, known only at load
 * time. So this is the whole of the transform, and everything after it is a map lookup. Two things here are the kind
 * of mistake that ships, because both look like success: a position transform that is off by the sign gives a
 * blueprint whose components are all a few blocks away, and a direction transform that mirrors and rotates in the
 * wrong order gives one whose repeaters all face the wrong way while every cell is right.
 */
class BlueprintPlacementTest {

    private static final BlockPos ANCHOR = new BlockPos(10, 64, -5);

    private static final List<Rotation> ROTATIONS = List.of(Rotation.NONE, Rotation.CLOCKWISE_90,
            Rotation.CLOCKWISE_180, Rotation.COUNTERCLOCKWISE_90);

    private static final List<Mirror> MIRRORS = List.of(Mirror.NONE, Mirror.LEFT_RIGHT, Mirror.FRONT_BACK);

    @Test
    @DisplayName("位置：12 种旋转×镜像组合都走原版的整数变换，不自己算")
    void everyCombinationIsVanillasOwnTransform() {
        for (Rotation rotation : ROTATIONS) {
            for (Mirror mirror : MIRRORS) {
                for (BlockPos relative : List.of(new BlockPos(0, 0, 0), new BlockPos(1, 0, 0),
                        new BlockPos(3, 7, 2), new BlockPos(0, 5, 11))) {
                    BlockPos expected = ANCHOR.offset(
                            StructureTemplate.transform(relative, mirror, rotation, BlockPos.ZERO));
                    assertEquals(expected, BlueprintPlacement.toWorld(ANCHOR, rotation, mirror, relative),
                            rotation + "/" + mirror + " 下 " + relative.toShortString() + " 的世界位置");
                }
            }
        }
    }

    @Test
    @DisplayName("位置：四个旋转与两个镜像各自的偏移，逐格钉住（含 −X/−Z 的负偏移）")
    void theOffsetsThemselvesAreRight() {
        // 不动：蓝图就是它自己。
        assertOffset(Rotation.NONE, Mirror.NONE, new BlockPos(1, 2, 3), new BlockPos(1, 2, 3));
        // 顺时针 90°：+X 转到 +Z，这正是 Direction#getClockWise 对 EAST 做的事。
        assertOffset(Rotation.CLOCKWISE_90, Mirror.NONE, new BlockPos(1, 0, 0), new BlockPos(0, 0, 1));
        // 逆时针 90°：+X 转到 −Z，蓝图因此往 −Z 长。
        assertOffset(Rotation.COUNTERCLOCKWISE_90, Mirror.NONE, new BlockPos(1, 0, 0), new BlockPos(0, 0, -1));
        // 180°：X 与 Z 同时反号，蓝图整个落在锚点的另一方。
        assertOffset(Rotation.CLOCKWISE_180, Mirror.NONE, new BlockPos(1, 2, 3), new BlockPos(-1, 2, -3));
        // LEFT_RIGHT 反 Z，FRONT_BACK 反 X，Y 谁都不动。
        assertOffset(Rotation.NONE, Mirror.LEFT_RIGHT, new BlockPos(1, 2, 3), new BlockPos(1, 2, -3));
        assertOffset(Rotation.NONE, Mirror.FRONT_BACK, new BlockPos(1, 2, 3), new BlockPos(-1, 2, 3));
        // 先镜像后旋转：+Z 上的格子先被翻到 −Z，再顺时针转到 +X。
        assertOffset(Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT, new BlockPos(0, 0, 3), new BlockPos(3, 0, 0));

        // 世界坐标本身可以是负的：锚点在原点、蓝图往 −Z 长时，元件落在 z = −1。
        BlockPos anchor = BlockPos.ZERO;
        assertEquals(new BlockPos(0, 0, -1), BlueprintPlacement.toWorld(anchor, Rotation.COUNTERCLOCKWISE_90,
                Mirror.NONE, new BlockPos(1, 0, 0)), "负偏移必须能过，不能夹到 0");
        assertEquals(new BlockPos(-1, 0, 0), BlueprintPlacement.toWorld(anchor, Rotation.CLOCKWISE_180,
                Mirror.NONE, new BlockPos(1, 0, 0)), "负偏移必须能过，不能夹到 0");
    }

    @Test
    @DisplayName("位置与朝向必须一起转：格子偏移等于朝向向量转过去的结果，六向十二组合无一例外")
    void aCellOffsetTurnsExactlyLikeItsFacing() {
        for (Rotation rotation : ROTATIONS) {
            for (Mirror mirror : MIRRORS) {
                for (Direction direction : Direction.values()) {
                    BlockPos turnedCell = StructureTemplate.transform(new BlockPos(direction.getNormal()), mirror,
                            rotation, BlockPos.ZERO);
                    Direction turnedFacing = BlueprintPlacement.toWorldDirection(direction, rotation, mirror);
                    assertEquals(new BlockPos(turnedFacing.getNormal()), turnedCell,
                            rotation + "/" + mirror + " 下 " + direction + " 的格子与朝向必须同向");
                }
            }
        }
    }

    @Test
    @DisplayName("朝向：镜像左右手性（先镜像后旋转），每个方向都是真实方块朝向")
    void theHandednessOfAMirrorIsTheVanillaOne() {
        // 不动。
        assertDirection(Direction.NORTH, Rotation.NONE, Mirror.NONE, Direction.NORTH);
        // 顺时针 90°：北→东→南→西。
        assertDirection(Direction.NORTH, Rotation.CLOCKWISE_90, Mirror.NONE, Direction.EAST);
        assertDirection(Direction.EAST, Rotation.CLOCKWISE_90, Mirror.NONE, Direction.SOUTH);
        assertDirection(Direction.NORTH, Rotation.CLOCKWISE_180, Mirror.NONE, Direction.SOUTH);
        assertDirection(Direction.NORTH, Rotation.COUNTERCLOCKWISE_90, Mirror.NONE, Direction.WEST);
        // LEFT_RIGHT 反 Z，所以南北互换、东西不动、上下不动。
        assertDirection(Direction.NORTH, Rotation.NONE, Mirror.LEFT_RIGHT, Direction.SOUTH);
        assertDirection(Direction.EAST, Rotation.NONE, Mirror.LEFT_RIGHT, Direction.EAST);
        assertDirection(Direction.UP, Rotation.NONE, Mirror.LEFT_RIGHT, Direction.UP);
        // FRONT_BACK 反 X：东西互换、南北不动。
        assertDirection(Direction.EAST, Rotation.NONE, Mirror.FRONT_BACK, Direction.WEST);
        assertDirection(Direction.NORTH, Rotation.NONE, Mirror.FRONT_BACK, Direction.NORTH);
        assertDirection(Direction.DOWN, Rotation.NONE, Mirror.FRONT_BACK, Direction.DOWN);
        // 顺序判别式：先镜像再旋转。LEFT_RIGHT 反 Z，所以东先原样不动，再顺时针成南；
        // 反过来先转再镜像则得北（东→顺时针→南→LEFT_RIGHT 反 Z→北）。两个答案不同，这一行才能钉住顺序。
        assertDirection(Direction.EAST, Rotation.CLOCKWISE_90, Mirror.LEFT_RIGHT, Direction.SOUTH);
        assertDirection(Direction.EAST, Rotation.COUNTERCLOCKWISE_90, Mirror.LEFT_RIGHT, Direction.NORTH);
        // 竖直方向在两个变换下都不动：镜像不能让上下互换。
        assertDirection(Direction.UP, Rotation.CLOCKWISE_180, Mirror.FRONT_BACK, Direction.UP);
    }

    @Test
    @DisplayName("元件：朝向与两组扳手锁一起转，原槽位不被改动")
    void theWrenchLocksTurnWithTheComponent() {
        Slot slot = new Slot(ComponentType.REPEATER);
        slot.facing = Direction.NORTH;
        slot.power = 7;
        slot.forcedOn.add(Direction.NORTH);
        slot.forcedOn.add(Direction.UP);
        slot.forcedOff.add(Direction.EAST);

        Slot turned = BlueprintPlacement.toWorldSlot(slot, Rotation.CLOCKWISE_90, Mirror.NONE);

        assertNotSame(slot, turned, "转出来的是新槽位");
        assertEquals(Direction.EAST, turned.facing, "朝向：北→东");
        assertEquals(java.util.Set.of(Direction.EAST, Direction.UP), turned.forcedOn,
                "连上的那两面一起转，竖直面不动");
        assertEquals(java.util.Set.of(Direction.SOUTH), turned.forcedOff, "切断的那一面一起转");
        assertEquals(7, turned.power, "其余字段原样");

        // 原槽位属于文件，不属于世界：转一次不能把它改掉。
        assertEquals(Direction.NORTH, slot.facing);
        assertEquals(java.util.Set.of(Direction.NORTH, Direction.UP), slot.forcedOn);
        assertEquals(java.util.Set.of(Direction.EAST), slot.forcedOff);
        assertTrue(turned.isRoutingLocked(), "锁还是锁：朝外的锁保留并随旋转变换，不过滤");
    }

    @Test
    @DisplayName("预算：一段元件的每个格子换到世界坐标，元件、时钟、延长器各归其表")
    void theBudgetIsTheWholeSectionInWorldCoordinates() {
        BlueprintComponents.Section section = new BlueprintComponents.Section(new Vec3i(3, 2, 4),
                List.of(new BlueprintComponents.Node(new BlockPos(0, 0, 0), node(ComponentType.DUST)),
                        new BlueprintComponents.Node(new BlockPos(1, 0, 0), node(ComponentType.REPEATER))),
                List.of(new BlueprintComponents.Clock(new BlockPos(2, 1, 3), ClockRule.Setting.of(5, 3, 11))),
                List.of(new BlueprintComponents.Extender(new BlockPos(0, 1, 2), ExtenderRule.Setting.of(2, 7))));

        BlockPos anchor = new BlockPos(0, 70, 0);
        BlueprintPlacement.Budget budget = BlueprintPlacement.budget(section, anchor,
                Rotation.COUNTERCLOCKWISE_90, Mirror.NONE);

        assertEquals(4, budget.size(), "两个元件、一只时钟、一个延长器");
        Map<BlockPos, Slot> nodes = budget.nodes();
        assertTrue(nodes.containsKey(new BlockPos(0, 70, 0)), "原点不动");
        // (1,0,0) 逆时针 90° 之后是 (0,0,-1)：世界坐标可以是负的。
        assertTrue(nodes.containsKey(new BlockPos(0, 70, -1)), "格子跟着旋转走，可以落到负坐标");
        assertEquals(ComponentType.REPEATER, nodes.get(new BlockPos(0, 70, -1)).type);
        assertTrue(budget.clocks().containsKey(new BlockPos(3, 71, -2)), "时钟的格子同样换算");
        assertEquals(ClockRule.Setting.of(5, 3, 11), budget.clocks().get(new BlockPos(3, 71, -2)));
        assertTrue(budget.extenders().containsKey(new BlockPos(2, 71, 0)), "延长器的格子同样换算");
        assertEquals(ExtenderRule.Setting.of(2, 7), budget.extenders().get(new BlockPos(2, 71, 0)));
    }

    @Test
    @DisplayName("降级：盒外的格子、空段、读不懂的文件 —— 不写、不抛")
    void everyDegenerateSectionIsSkippedRatherThanThrown() {
        // 盒外的格子：这一份是代码里造出来的（读文件时已经被拒），这里也不能给出一个世界坐标。
        BlueprintComponents.Section outside = new BlueprintComponents.Section(new Vec3i(1, 1, 1),
                List.of(new BlueprintComponents.Node(new BlockPos(3, 0, 0), node(ComponentType.DUST))),
                List.of(new BlueprintComponents.Clock(new BlockPos(0, 0, 9), ClockRule.Setting.DEFAULT)),
                List.of());
        BlueprintPlacement.Budget budget = BlueprintPlacement.budget(outside, BlockPos.ZERO, Rotation.NONE,
                Mirror.NONE);
        assertTrue(budget.isEmpty(), "盒外一个格子都不能落进世界坐标");

        // 版本不认识。
        assertNull(BlueprintDeploy.budgetOf(tagged(99, 1, 1, 1), BlockPos.ZERO, Rotation.NONE, Mirror.NONE),
                "不认识的版本：整段丢弃");
        // size 与文件自己的对不上。
        assertNull(BlueprintDeploy.budgetOf(tagged(BlueprintComponents.VERSION, 4, 4, 4), BlockPos.ZERO,
                Rotation.NONE, Mirror.NONE), "尺寸对不上：整段丢弃");
        // 顶层没有这一段。
        assertNull(BlueprintDeploy.budgetOf(new CompoundTag(), BlockPos.ZERO, Rotation.NONE, Mirror.NONE),
                "没有这一段就是没有元件");
        assertNull(BlueprintDeploy.budgetOf(null, BlockPos.ZERO, Rotation.NONE, Mirror.NONE), "空文件");
        assertNull(BlueprintDeploy.budgetOf(tagged(BlueprintComponents.VERSION, 1, 1, 1), null, Rotation.NONE,
                Mirror.NONE), "没有锚点就没有世界坐标");
    }

    // --------------------------------------------------------------- helpers --

    private static void assertOffset(Rotation rotation, Mirror mirror, BlockPos relative, BlockPos offset) {
        assertEquals(ANCHOR.offset(offset), BlueprintPlacement.toWorld(ANCHOR, rotation, mirror, relative),
                rotation + "/" + mirror + " 下 " + relative.toShortString() + " 的偏移");
    }

    private static void assertDirection(Direction from, Rotation rotation, Mirror mirror, Direction expected) {
        assertEquals(expected, BlueprintPlacement.toWorldDirection(from, rotation, mirror),
                rotation + "/" + mirror + " 下 " + from + " 转成什么");
    }

    private static InnerRedstoneNode node(ComponentType type) {
        return InnerRedstoneNode.of(type);
    }

    /** A file that carries a section whose own size is {@code size} but whose file size says 1x1x1. */
    private static CompoundTag tagged(int version, int sizeX, int sizeY, int sizeZ) {
        CompoundTag section = new CompoundTag();
        section.putInt("v", version);
        section.putIntArray("size", new int[] {sizeX, sizeY, sizeZ});
        section.put("nodes", new net.minecraft.nbt.ListTag());
        section.put("clocks", new net.minecraft.nbt.ListTag());
        section.put("extenders", new net.minecraft.nbt.ListTag());
        CompoundTag file = new CompoundTag();
        file.put("redstonecircuit", section);
        net.minecraft.nbt.ListTag fileSize = new net.minecraft.nbt.ListTag();
        fileSize.add(net.minecraft.nbt.IntTag.valueOf(1));
        fileSize.add(net.minecraft.nbt.IntTag.valueOf(1));
        fileSize.add(net.minecraft.nbt.IntTag.valueOf(1));
        file.put("size", fileSize);
        return file;
    }

    @Test
    @DisplayName("预算的两张表是独立的：元件不会混进时钟，反之亦然")
    void theThreeTablesDoNotLeakIntoEachOther() {
        BlueprintComponents.Section section = new BlueprintComponents.Section(new Vec3i(1, 1, 1),
                List.of(new BlueprintComponents.Node(BlockPos.ZERO, node(ComponentType.CLOCK))),
                List.of(new BlueprintComponents.Clock(BlockPos.ZERO, ClockRule.Setting.of(2, 2, 2))),
                List.of(new BlueprintComponents.Extender(BlockPos.ZERO, ExtenderRule.Setting.of(3, 3))));
        BlueprintPlacement.Budget budget = BlueprintPlacement.budget(section, BlockPos.ZERO, Rotation.NONE,
                Mirror.NONE);
        assertEquals(1, budget.nodes().size());
        assertEquals(1, budget.clocks().size());
        assertEquals(1, budget.extenders().size());
        assertFalse(budget.isEmpty());
        assertSame(ComponentType.CLOCK, budget.nodes().get(BlockPos.ZERO).type);
    }
}

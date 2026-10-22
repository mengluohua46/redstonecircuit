package com.jiangyuefengyu.redstonecircuit.blueprint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.ControllerRule;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The blueprint's inner-redstone section: what it carries, and what it refuses.
 *
 * <p>Both halves are needed because the file outlives the build that wrote it. What goes in has to come back out
 * unchanged - a repeater that comes back facing north, or a clock that comes back at one second, would be a blueprint
 * that silently builds the wrong machine. What does not come back out has to be <b>nothing</b>: a version this build
 * does not know, a size that disagrees with the file, a key of the wrong type, a position outside the box. Every one of
 * those is other people's data arriving in a file, so the answer is a value and never an exception - the blueprint
 * still places, it just places what its block list says.
 */
class BlueprintComponentsTest {

    private static final Vec3i SIZE = new Vec3i(5, 4, 3);

    @Test
    @DisplayName("元件逐字段过文件：朝向、强制通断、时钟、延长器、控制器、变压器都不许漂移")
    void everyComponentFieldSurvivesTheFile() {
        InnerRedstoneNode node = busyNode();
        BlueprintComponents.Node entry = new BlueprintComponents.Node(new BlockPos(1, 2, 2), node);
        BlueprintComponents.Clock clock = new BlueprintComponents.Clock(new BlockPos(0, 0, 0),
                new ClockRule.Setting(12, 7, 9));
        BlueprintComponents.Extender extender = new BlueprintComponents.Extender(new BlockPos(4, 3, 2),
                new ExtenderRule.Setting(30, 4));

        CompoundTag section = BlueprintComponents.write(SIZE, List.of(entry), List.of(clock), List.of(extender));
        BlueprintComponents.Section read = BlueprintComponents.readFile(blueprint(section, SIZE));

        assertNotNull(read, "元件表必须能从文件里读回来");
        assertEquals(SIZE, read.size());

        assertEquals(1, read.nodes().size(), "一个宿主方块");
        assertEquals(new BlockPos(1, 2, 2), read.nodes().get(0).pos(), "位置就是盒内相对坐标");
        assertSameSlot(node.slot(), read.nodes().get(0).node().slot());

        assertEquals(1, read.clocks().size(), "一只时钟源");
        assertEquals(new BlockPos(0, 0, 0), read.clocks().get(0).pos());
        assertEquals(clock.setting(), read.clocks().get(0).setting(), "秒/刻/强度三件都要原样回来");

        assertEquals(1, read.extenders().size(), "一个信号延长器");
        assertEquals(new BlockPos(4, 3, 2), read.extenders().get(0).pos());
        assertEquals(extender.setting(), read.extenders().get(0).setting(), "秒/刻两件都要原样回来");
    }

    @Test
    @DisplayName("降级：版本号不认识、size 与文件对不上，整段丢弃")
    void aVersionOrASizeThisBuildDisagreesWithDropsTheWholeSection() {
        CompoundTag section = sampleSection();
        assertNotNull(BlueprintComponents.read(section, SIZE), "先确认这一份本来读得回来");

        CompoundTag newer = section.copy();
        newer.putInt(BlueprintComponents.VERSION_KEY, BlueprintComponents.VERSION + 1);
        assertNull(BlueprintComponents.read(newer, SIZE),
                "版本号不认识的段就是别人写的段，一个字段都不许用");

        CompoundTag other = section.copy();
        other.putIntArray(BlueprintComponents.SIZE, new int[] {5, 4, 4});
        assertNull(BlueprintComponents.read(other, SIZE),
                "段里的 size 与文件自己的 size 不一致 = 这一份不是从这只盒子出来的，整段作废");
        assertNull(BlueprintComponents.readFile(blueprint(section, new Vec3i(5, 4, 4))),
                "反过来也一样：文件说盒子是另一个尺寸，段也跟着作废");
    }

    @Test
    @DisplayName("降级：缺键、类型错、位置出盒，一律整段丢弃")
    void aMissingOrMistypedKeyDropsTheWholeSection() {
        for (String key : List.of(BlueprintComponents.SIZE, BlueprintComponents.NODES,
                BlueprintComponents.CLOCKS, BlueprintComponents.EXTENDERS)) {
            CompoundTag missing = sampleSection();
            missing.remove(key);
            assertNull(BlueprintComponents.read(missing, SIZE), "缺 " + key + " 的段不许被当成半份数据用");
        }

        CompoundTag wrongVersion = sampleSection();
        wrongVersion.putString(BlueprintComponents.VERSION_KEY, "1");
        assertNull(BlueprintComponents.read(wrongVersion, SIZE), "版本号是字符串就是类型错");

        CompoundTag wrongNodes = sampleSection();
        ListTag ints = new ListTag();
        ints.add(IntTag.valueOf(7));
        wrongNodes.put(BlueprintComponents.NODES, ints);
        assertNull(BlueprintComponents.read(wrongNodes, SIZE), "元件表的元素不是复合标签就是类型错");

        CompoundTag wrongSize = sampleSection();
        wrongSize.putString(BlueprintComponents.SIZE, "5,4,3");
        assertNull(BlueprintComponents.read(wrongSize, SIZE), "size 不是三个整数就是类型错");

        CompoundTag outside = sampleSection();
        CompoundTag moved = ((ListTag) outside.get(BlueprintComponents.NODES)).getCompound(0).copy();
        moved.putIntArray(BlueprintComponents.POS, new int[] {5, 0, 0});
        ((ListTag) outside.get(BlueprintComponents.NODES)).set(0, moved);
        assertNull(BlueprintComponents.read(outside, SIZE),
                "x=5 的盒子最后一格是 x=4：出盒的位置根本不存在，整段作废");

        CompoundTag shortPos = sampleSection();
        CompoundTag truncated = ((ListTag) shortPos.get(BlueprintComponents.NODES)).getCompound(0).copy();
        truncated.putIntArray(BlueprintComponents.POS, new int[] {1, 2});
        ((ListTag) shortPos.get(BlueprintComponents.NODES)).set(0, truncated);
        assertNull(BlueprintComponents.read(shortPos, SIZE), "p 不是三个整数就是类型错");

        CompoundTag noComponent = sampleSection();
        CompoundTag bare = ((ListTag) noComponent.get(BlueprintComponents.NODES)).getCompound(0).copy();
        bare.remove("component");
        ((ListTag) noComponent.get(BlueprintComponents.NODES)).set(0, bare);
        assertNull(BlueprintComponents.read(noComponent, SIZE),
                "有位置却没有元件 = 作者想说的不是这一件事，不许猜");

        assertNull(BlueprintComponents.readFile(new CompoundTag()),
                "一个连 size 都没有的文件不是蓝图，段也谈不上有");
        assertNull(BlueprintComponents.readFile(blueprint(null, SIZE)),
                "蓝图里没有我们的键 = 这只蓝图没有带元件，这不是错误");
        assertNull(BlueprintComponents.read(null, SIZE), "null 段就是没有段");
    }

    @Test
    @DisplayName("降级：任何读不懂的东西都只换回 null，不抛异常")
    void nothingAFileCanContainThrows() {
        CompoundTag hostile = sampleSection();
        hostile.put(BlueprintComponents.CLOCKS, StringTag.valueOf("not a list"));
        hostile.put(BlueprintComponents.EXTENDERS, new CompoundTag());
        assertNull(assertDoesNotThrow(() -> BlueprintComponents.read(hostile, SIZE)));

        ListTag nodes = new ListTag();
        CompoundTag chaos = new CompoundTag();
        chaos.put(BlueprintComponents.POS, new ListTag());
        chaos.put("component", StringTag.valueOf("x"));
        nodes.add(chaos);
        CompoundTag nested = sampleSection();
        nested.put(BlueprintComponents.NODES, nodes);
        assertNull(assertDoesNotThrow(() -> BlueprintComponents.read(nested, SIZE)));

        CompoundTag empty = sampleSection();
        empty.put(BlueprintComponents.NODES, new ListTag());
        empty.put(BlueprintComponents.CLOCKS, new ListTag());
        empty.put(BlueprintComponents.EXTENDERS, new ListTag());
        BlueprintComponents.Section none = assertDoesNotThrow(() -> BlueprintComponents.read(empty, SIZE));
        assertNotNull(none, "三个空表仍然是一份合法的段 - 它就是'这只蓝图没有元件'");
        assertEquals(0, none.entryCount());
    }

    @Test
    @DisplayName("段里只放元件：不重复存方块状态、palette、indices、uuid 这些蓝图已经有的东西")
    void theSectionCarriesNothingTheBlueprintAlreadyCarries() {
        CompoundTag section = sampleSection();
        assertEquals(5, section.getAllKeys().size(),
                "段里只有 v / size / nodes / clocks / extenders，实际是 " + section.getAllKeys());
        for (String forbidden : List.of("blocks", "palette", "palettes", "entities", "DataVersion", "indices",
                "context_shell", "uuid", "hash", "anchor", "rotation", "mirror")) {
            assertTrue(!section.contains(forbidden), "段里不许出现 " + forbidden + " - 蓝图自己已经有了");
        }
        CompoundTag clockTag = ((ListTag) section.get(BlueprintComponents.CLOCKS)).getCompound(0);
        assertTrue(clockTag.contains("seconds") && clockTag.contains("ticks") && clockTag.contains("p"),
                "时钟源的入口就是 p + 它自己的 Setting 形状，实际是 " + clockTag.getAllKeys());
    }

    // ---------------------------------------------------------------- fixtures --

    /** A whole blueprint the way Create writes one, with this mod's section at the top level. */
    private static CompoundTag blueprint(CompoundTag section, Vec3i size) {
        CompoundTag blueprint = new CompoundTag();
        blueprint.put("blocks", new ListTag());
        blueprint.put("palette", new ListTag());
        ListTag sizeTag = new ListTag();
        sizeTag.add(IntTag.valueOf(size.getX()));
        sizeTag.add(IntTag.valueOf(size.getY()));
        sizeTag.add(IntTag.valueOf(size.getZ()));
        blueprint.put("size", sizeTag);
        blueprint.putInt("DataVersion", 3955);
        if (section != null) {
            blueprint.put(BlueprintComponents.KEY, section);
        }
        return blueprint;
    }

    private static CompoundTag sampleSection() {
        InnerRedstoneNode node = busyNode();
        return BlueprintComponents.write(SIZE,
                List.of(new BlueprintComponents.Node(new BlockPos(1, 2, 2), node)),
                List.of(new BlueprintComponents.Clock(new BlockPos(0, 0, 0), new ClockRule.Setting(12, 7, 9))),
                List.of(new BlueprintComponents.Extender(new BlockPos(4, 3, 2),
                        new ExtenderRule.Setting(30, 4))));
    }

    private static InnerRedstoneNode busyNode() {
        InnerRedstoneNode node = InnerRedstoneNode.of(ComponentType.CLOCK);
        node.setSlot(busySlot());
        return node;
    }

    /** A component with every saved field set to something that is not its default. */
    private static Slot busySlot() {
        Slot slot = new Slot(ComponentType.CLOCK);
        slot.power = 11;
        slot.powered = true;
        slot.facing = Direction.EAST;
        slot.delay = 3;
        slot.mode = ComparatorMode.SUBTRACT;
        slot.controller = new ControllerRule.Setting(ControllerRule.Main.LESS, ControllerRule.Sub.NOT_EQUAL, 7, 9);
        slot.transformer = 5;
        slot.clock = new ClockRule.Setting(12, 7, 9);
        slot.extender = new ExtenderRule.Setting(30, 4);
        slot.setConnection(Direction.UP, ConnectionState.ON);
        slot.setConnection(Direction.DOWN, ConnectionState.OFF);
        return slot;
    }

    private static void assertSameSlot(Slot expected, Slot actual) {
        assertNotNull(actual, "元件必须过线");
        assertEquals(expected.type, actual.type);
        assertEquals(expected.power, actual.power, "强度");
        assertEquals(expected.powered, actual.powered, "开关");
        assertEquals(expected.facing, actual.facing, "朝向按名字走，不许变成 ordinal");
        assertEquals(expected.delay, actual.delay, "中继器档位");
        assertEquals(expected.mode, actual.mode, "比较器模式");
        assertEquals(expected.controller, actual.controller, "控制器四个设定");
        assertEquals(expected.transformer, actual.transformer, "变压器输出强度");
        assertEquals(expected.clock, actual.clock, "时钟源三个数");
        assertEquals(expected.extender, actual.extender, "延长器两个数");
        assertEquals(expected.forcedOn, actual.forcedOn, "强制接通的方向");
        assertEquals(expected.forcedOff, actual.forcedOff, "强制切断的方向");
    }
}

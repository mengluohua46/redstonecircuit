package com.jiangyuefengyu.redstonecircuit.logic;

import static com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Main.GREATER;
import static com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Main.LESS;
import static com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Sub.EQUAL;
import static com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Sub.NOT_EQUAL;
import static com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Sub.ONLY_EQUAL;
import static com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Sub.ONLY_NOT_EQUAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>控制器's four sentences, as a table.</b>
 *
 * <p>满足条件即输出指定红石信号：主档位（大于/小于）与副档位（等于/不等于/只等于/只不等于）比较玩家输入与档位，条件成立
 * 就输出设定的强度。不等于 = 主档位但不含边界，只等于/只不等于则完全不看主档位 - 这两条是整张表的关键。
 */
class ControllerRuleTest {

    @Test
    @DisplayName("原例：主档位大于 + 副档位等于 + 档位7 ⇒ 输入≥7 输出15")
    void theAuthorsOwnExample() {
        assertEquals(15, ControllerRule.answer(GREATER, EQUAL, 7, 7, 15), "正好等于也要输出：大于等于 7");
        assertEquals(15, ControllerRule.answer(GREATER, EQUAL, 8, 7, 15));
        assertEquals(15, ControllerRule.answer(GREATER, EQUAL, 15, 7, 15));
        assertEquals(0, ControllerRule.answer(GREATER, EQUAL, 6, 7, 15), "差一格就是差一格");
        assertEquals(0, ControllerRule.answer(GREATER, EQUAL, 0, 7, 15));
    }

    @Test
    @DisplayName("不等于 = 主档位但边界不算（正好等于档位时不输出）")
    void notEqualIsTheMainComparisonWithoutItsBoundary() {
        assertEquals(0, ControllerRule.answer(GREATER, NOT_EQUAL, 7, 7, 15), "「严格大于」，7 本身不算");
        assertEquals(15, ControllerRule.answer(GREATER, NOT_EQUAL, 8, 7, 15));
        assertEquals(15, ControllerRule.answer(GREATER, NOT_EQUAL, 15, 7, 15));
        assertEquals(0, ControllerRule.answer(GREATER, NOT_EQUAL, 6, 7, 15));

        assertEquals(0, ControllerRule.answer(LESS, NOT_EQUAL, 7, 7, 15), "主档位小于时是「严格小于」");
        assertEquals(15, ControllerRule.answer(LESS, NOT_EQUAL, 6, 7, 15));
        assertEquals(0, ControllerRule.answer(LESS, NOT_EQUAL, 8, 7, 15));
    }

    @Test
    @DisplayName("只等于 / 只不等于：主档位完全不参与（两个主档位下答案一模一样）")
    void theTwoOnlySettingsIgnoreTheMainLevel() {
        for (int input = 0; input <= ControllerRule.MAX; input++) {
            boolean equal = input == 7;
            for (ControllerRule.Main main : ControllerRule.Main.values()) {
                assertEquals(equal ? 15 : 0, ControllerRule.answer(main, ONLY_EQUAL, input, 7, 15),
                        "只等于，主档位=" + main.key() + "，输入=" + input);
                assertEquals(equal ? 0 : 15, ControllerRule.answer(main, ONLY_NOT_EQUAL, input, 7, 15),
                        "只不等于，主档位=" + main.key() + "，输入=" + input);
            }
        }
    }

    @Test
    @DisplayName("四个副档位 × 两个主档位 × 输入0-15，逐格对一遍")
    void theWholeTable() {
        int level = 7;
        int out = 15;
        for (int input = 0; input <= ControllerRule.MAX; input++) {
            boolean greater = input > level;
            boolean less = input < level;
            boolean equal = input == level;
            String where = "输入=" + input + "，档位=" + level;
            assertEquals(greater || equal ? out : 0, ControllerRule.answer(GREATER, EQUAL, input, level, out),
                    "大于+等于 ⇒ ≥，" + where);
            assertEquals(less || equal ? out : 0, ControllerRule.answer(LESS, EQUAL, input, level, out),
                    "小于+等于 ⇒ ≤，" + where);
            assertEquals(greater ? out : 0, ControllerRule.answer(GREATER, NOT_EQUAL, input, level, out),
                    "大于+不等于 ⇒ >，" + where);
            assertEquals(less ? out : 0, ControllerRule.answer(LESS, NOT_EQUAL, input, level, out),
                    "小于+不等于 ⇒ <，" + where);
            assertEquals(equal ? out : 0, ControllerRule.answer(GREATER, ONLY_EQUAL, input, level, out),
                    "只等于 ⇒ =，" + where);
            assertEquals(equal ? 0 : out, ControllerRule.answer(GREATER, ONLY_NOT_EQUAL, input, level, out),
                    "只不等于 ⇒ ≠，" + where);
        }
    }

    @Test
    @DisplayName("档位 0 是一个正常设置：大于+等于+0 时没有输入也会输出（所以方块默认档位是 1）")
    void levelZeroIsSatisfiedByNothingAtAll() {
        assertEquals(15, ControllerRule.answer(GREATER, EQUAL, 0, 0, 15));
        assertEquals(0, ControllerRule.answer(GREATER, NOT_EQUAL, 0, 0, 15), "而「严格大于 0」才是「有任何信号」");
        assertEquals(15, ControllerRule.answer(GREATER, NOT_EQUAL, 1, 0, 15));
    }

    @Test
    @DisplayName("输出的是玩家设的强度，不是固定 15；从世界读到的东西先夹进 0-15")
    void theOutputIsThePlayersStrength() {
        assertEquals(3, ControllerRule.answer(GREATER, EQUAL, 15, 1, 3));
        assertEquals(1, ControllerRule.answer(GREATER, EQUAL, 15, 1, 0), "0 不是可设的输出强度，夹成 1");
        assertEquals(15, ControllerRule.answer(GREATER, EQUAL, 15, 1, 99));
        assertEquals(15, ControllerRule.answer(GREATER, EQUAL, 99, 1, 15), "越界的输入按 15 算");
        assertEquals(0, ControllerRule.answer(GREATER, EQUAL, -4, 1, 15), "负的输入按 0 算");
        assertTrue(ControllerRule.isOutput(1));
        assertTrue(ControllerRule.isOutput(15));
        assertFalse(ControllerRule.isOutput(0), "「输出 0 强度」不是设置，而是「不输出」");
        assertTrue(ControllerRule.isThreshold(0), "而档位 0 是可以设的");
        assertFalse(ControllerRule.isThreshold(16));
    }

    @Test
    @DisplayName("条件那句话就是界面显示的那句：≥ / > / ≤ / < / = / ≠")
    void theConditionText() {
        assertEquals("≥ 7", ControllerRule.condition(GREATER, EQUAL, 7));
        assertEquals("> 7", ControllerRule.condition(GREATER, NOT_EQUAL, 7));
        assertEquals("≤ 7", ControllerRule.condition(LESS, EQUAL, 7));
        assertEquals("< 7", ControllerRule.condition(LESS, NOT_EQUAL, 7));
        assertEquals("= 7", ControllerRule.condition(GREATER, ONLY_EQUAL, 7));
        assertEquals("≠ 7", ControllerRule.condition(LESS, ONLY_NOT_EQUAL, 7), "只等于/只不等于与主档位无关");
        assertEquals("= 0", ControllerRule.condition(GREATER, ONLY_EQUAL, 0), "档位是 0 时也照实写出来");
    }

    @Test
    @DisplayName("Setting：世界里的薄板和方块里的元件问的是同一张表，而且它跟着元件存盘")
    void theSettingIsTheSameRuleEverywhere() {
        ControllerRule.Setting setting = new ControllerRule.Setting(LESS, EQUAL, 7, 3);
        assertEquals(3, setting.answer(7), "≤7 ⇒ 输出玩家设的 3");
        assertEquals(3, setting.answer(0));
        assertEquals(0, setting.answer(8));
        assertEquals("≤ 7", setting.condition());

        // Clamping: a save file cannot carry a setting the rule has no meaning for.
        ControllerRule.Setting wild = new ControllerRule.Setting(null, null, 90, 0).clamped();
        assertEquals(ControllerRule.Main.GREATER, wild.main(), "没有主档位 ⇒ 用默认的那个");
        assertEquals(ControllerRule.Sub.EQUAL, wild.sub());
        assertEquals(15, wild.threshold());
        assertEquals(1, wild.output(), "输出强度 0 不是设置，夹成 1");

        // Slot round trip: the four settings travel with the component.
        Slot slot = new Slot(ComponentType.CONTROLLER);
        slot.controller = setting;
        CompoundTag tag = new CompoundTag();
        slot.save(tag);
        assertEquals(setting, Slot.load(tag).controller, "存盘读回来要一模一样");
        assertEquals(setting, slot.copy().controller, "copy 也要带上");

        // A save with no controller tag at all reads as the default, not as null.
        assertEquals(ControllerRule.Setting.DEFAULT, Slot.load(new CompoundTag()).controller);
    }

    @Test
    @DisplayName("方块内形态的接线：从正面读、往背面出、弱信号，而且它的输入面不会被扳手的锁拿走")
    void theInnerFormReadsTheSameWay() {
        assertTrue(ComponentType.CONTROLLER.hasInputSide(), "它有一个自己的输入面");
        assertFalse(ComponentType.CONTROLLER.isDiode(),
                "但它不是二极管：二极管会往正前方的方块里灌强信号（directSignalToward），它不会");
        assertEquals(InnerSwitches.Gesture.SETTING, InnerSwitches.gestureOf(ComponentType.CONTROLLER),
                "它属于「要戴红石眼镜才能调」的那一类，我们自己模组的同类元件也算在内");
        assertFalse(InnerSwitches.isHandSwitch(ComponentType.CONTROLLER), "不是拉杆/按钮那种徒手就用的");

        assertTrue(PowerSolver.readsToward(ComponentType.CONTROLLER, Direction.NORTH, Direction.NORTH,
                false, false, false), "从 facing 那一面读");
        assertFalse(PowerSolver.readsToward(ComponentType.CONTROLLER, Direction.NORTH, Direction.SOUTH,
                false, false, false), "别的面不读");
        assertTrue(PowerSolver.emitsToward(ComponentType.CONTROLLER, Direction.NORTH, Direction.SOUTH,
                false, false, false), "往背面出");
        assertFalse(PowerSolver.emitsToward(ComponentType.CONTROLLER, Direction.NORTH, Direction.NORTH,
                false, false, false), "不往正面出");
        assertFalse(PowerSolver.directSignalToward(ComponentType.CONTROLLER, Direction.NORTH, Direction.SOUTH, false),
                "弱信号：不充能方块（和逻辑整合器/传输门同一口径）");
        assertTrue(PowerSolver.readsToward(ComponentType.CONTROLLER, Direction.NORTH, Direction.NORTH,
                false, false, true), "扳手锁了别处，也不能把它的输入面一起锁掉 - 那是它自己的事");
    }
}

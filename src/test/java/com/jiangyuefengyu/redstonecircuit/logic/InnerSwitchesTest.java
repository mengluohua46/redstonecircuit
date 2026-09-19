package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests which components a bare hand can work, and what the settings mean.
 *
 * <p>The state change itself needs a level and is covered in game, but two things are worth pinning
 * here: which types answer a bare-hand click at all (a wrong answer would either eat a click that
 * belongs to vanilla, or silently do nothing), and that the displayed text describes the state that was
 * actually set - the action bar is the only feedback a player gets without the goggles.
 */
class InnerSwitchesTest {

    private static Slot slot(ComponentType type) {
        return new Slot(type);
    }

    @Test
    @DisplayName("a bare hand can work switches and both diode settings, and nothing else")
    void workableTypes() {
        assertTrue(InnerSwitches.isWorkable(ComponentType.LEVER));
        assertTrue(InnerSwitches.isWorkable(ComponentType.BUTTON));
        assertTrue(InnerSwitches.isWorkable(ComponentType.REPEATER),
                "a repeater's delay is a player setting, exactly as on the ground");
        assertTrue(InnerSwitches.isWorkable(ComponentType.COMPARATOR),
                "and so is a comparator's mode");

        assertFalse(InnerSwitches.isWorkable(ComponentType.DUST),
                "a wire has nothing to adjust");
        assertFalse(InnerSwitches.isWorkable(ComponentType.TORCH),
                "and neither does a torch: it follows its input");
        assertFalse(InnerSwitches.isWorkable(ComponentType.PRESSURE_PLATE),
                "a plate answers to entities, not to clicks");
    }

    /**
     * Tests the readouts by their translation key and arguments rather than by rendered text.
     *
     * <p>There is no language file loaded in a headless test, so the text itself cannot be checked here;
     * what can be checked - and what actually breaks - is which message is chosen and what is handed to
     * it, since that readout is the only feedback a player gets without the goggles on.
     */
    private static TranslatableContents contents(Slot slot) {
        return (TranslatableContents) InnerSwitches.describe(slot).getContents();
    }

    @Test
    @DisplayName("the delay readout names the setting and what it costs in ticks")
    void delayReadout() {
        Slot repeater = slot(ComponentType.REPEATER);
        repeater.delay = 3;
        TranslatableContents text = contents(repeater);

        assertEquals("message.redstonecircuit.switch.repeater", text.getKey());
        assertEquals(2, text.getArgs().length, "the setting and its tick cost");
        assertEquals("3", ((Component) text.getArgs()[0]).getString(), "the setting");
        assertEquals("6", ((Component) text.getArgs()[1]).getString(),
                "and 2 ticks per setting, as vanilla's delay is");
    }

    @Test
    @DisplayName("the comparator readout names the mode it is in")
    void comparatorReadout() {
        Slot comparator = slot(ComponentType.COMPARATOR);
        comparator.mode = ComparatorMode.COMPARE;
        String compare = contents(comparator).getKey();

        comparator.mode = ComparatorMode.SUBTRACT;
        String subtract = contents(comparator).getKey();

        assertFalse(compare.equals(subtract), "the two modes must not read the same");
        assertTrue(compare.endsWith(".compare"), compare);
        assertTrue(subtract.endsWith(".subtract"), subtract);
    }

    @Test
    @DisplayName("a switch reads as on or off, not as a delay")
    void leverReadout() {
        Slot lever = slot(ComponentType.LEVER);
        lever.facing = Direction.NORTH;
        lever.powered = true;
        String on = contents(lever).getKey();
        lever.powered = false;
        String off = contents(lever).getKey();

        assertFalse(on.equals(off));
        assertTrue(on.endsWith(".on"), on);
        assertTrue(off.endsWith(".off"), off);
    }
}

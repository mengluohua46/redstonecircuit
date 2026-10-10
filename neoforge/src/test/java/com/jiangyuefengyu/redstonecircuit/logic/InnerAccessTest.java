package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

/**
 * Working what is inside a block needs the goggles.
 *
 * <p>The rule is one line and lives in {@link InnerAccess}; these two tests are the table and the default, because "a new
 * component is covered too" is a claim about the <em>rule</em> being asked in one place rather than about any component -
 * see {@code InnerAdjustGogglesTest} for the two doors that ask it.
 */
class InnerAccessTest {

    @Test
    @DisplayName("with the goggles on it always works, and without them the setting and the whitelist decide")
    void theGogglesDecide() {
        assertTrue(InnerAccess.mayAdjust(true, true, false),
                "goggles on: a delay steps, a mode swaps - the item is what lets a player work inside a block");
        assertFalse(InnerAccess.mayAdjust(true, false, false),
                "the default: the same clicks do nothing at all without them");
        assertTrue(InnerAccess.mayAdjust(true, false, true),
                "and the whitelist: a lever or a button is worked without the goggles, because flipping one is the "
                        + "whole of what it is - see InnerSwitches#isHandSwitch");
        assertTrue(InnerAccess.mayAdjust(false, false, false),
                "with the setting off a bare hand works as it always did, which is what makes this a setting");
        assertTrue(InnerAccess.mayAdjust(false, true, false),
                "wearing them changes nothing when they are not required");
    }

    @Test
    @DisplayName("the whitelist is the switch kinds only, and it is the same fact the toggle acts on")
    void theWhitelistIsTheSwitchKinds() {
        for (ComponentType type : ComponentType.values()) {
            InnerSwitches.Gesture gesture = InnerSwitches.gestureOf(type);
            boolean expected = type == ComponentType.LEVER || type == ComponentType.BUTTON;
            assertTrue(InnerSwitches.isHandSwitch(type) == expected,
                    type + " must be a hand switch exactly when it is a lever or a button");
            assertTrue(InnerSwitches.isWorkable(type) == (gesture != InnerSwitches.Gesture.NONE),
                    type + " is workable exactly when it has a gesture at all - the toggle switches on this same "
                            + "classification, so the whitelist cannot drift away from the actions it whitelists");
            if (gesture == InnerSwitches.Gesture.SETTING) {
                assertFalse(InnerSwitches.isHandSwitch(type),
                        type + " is a setting: its whole meaning is a value, and a value needs the goggles to be read");
            }
        }
        assertTrue(InnerSwitches.gestureOf(ComponentType.LEVER) == InnerSwitches.Gesture.SWITCH
                        && InnerSwitches.gestureOf(ComponentType.COMPARATOR) == InnerSwitches.Gesture.SETTING,
                "and the two ends of the table are what they say they are");
    }

    @Test
    @DisplayName("the setting is on by default")
    void requiredByDefault() {
        assertTrue(RCConfig.innerAdjustNeedsGoggles(),
                "an unloaded config must answer the default, and the default is: no goggles, no "
                        + "adjusting what is inside a block");
    }
}

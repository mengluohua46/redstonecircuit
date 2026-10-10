package com.jiangyuefengyu.redstonecircuit.logic;

/**
 * Who may work what is inside a block: the goggles let a player see inside a block, so they are also what lets a
 * player <em>work</em> what is in there - flick a lever, press a button, step a repeater's delay, swap a comparator's
 * mode. The rule lives here rather than on the components, since a rule per component is one every new component must
 * remember; what is inside a block is adjusted through exactly two gestures (a bare hand on a world host, a bare hand
 * on a host riding a machine) and both ask this class first.
 * <p>{@code requires} is {@code RCConfig#innerAdjustNeedsGoggles} and {@code wearing} is
 * {@code RCRegistry#isWearingGoggles}, the same wearer question the renderer asks, so "cannot see inside" and "cannot
 * work inside" can never disagree.
 */
public final class InnerAccess {

    /**
     * Allowed when the setting is off, the goggles are on, or the component's whole meaning is the gesture itself (a
     * lever or a button); a repeater's delay or a comparator's mode is a value nobody should change unseen.
     */
    public static boolean mayAdjust(boolean requiresGoggles, boolean wearingGoggles, boolean handSwitch) {
        return !requiresGoggles || wearingGoggles || handSwitch;
    }

    private InnerAccess() {
    }
}

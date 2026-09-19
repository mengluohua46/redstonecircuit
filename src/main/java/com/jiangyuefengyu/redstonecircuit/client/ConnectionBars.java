package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Box;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Neighbours;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.Direction;

/**
 * The bars that show which sides of a component inside a block are connected.
 *
 * <h2>Why this is worth drawing</h2>
 * Two pieces of inner wire look identical however they are routed, and the whole point of the wrench
 * (R7) is to change that routing. So the drawing has to show it, and it has to show the difference
 * between the three states the wrench walks through:
 *
 * <pre>
 *   connected   a bar in the component's own colour, reaching the block's face
 *   pinned      the same bar, in green, whether or not anything is there yet
 *   cut         a short dark stub, so "blocked here" is visible rather than merely absent
 * </pre>
 *
 * <p>Which sides those are comes from {@link PowerSolver} itself rather than from a second set of
 * rules, so the drawing cannot disagree with the solver about where a repeater points. The one thing
 * the solver cannot answer is what is in the neighbouring blocks, so that is passed in.
 */
final class ConnectionBars {

    /** A side the wrench pinned on. */
    private static final int PIN_COLOR = 0xFF35E05A;
    /** A side the wrench cut. */
    private static final int CUT_COLOR = 0xFF303030;
    /** The side a driven component reads from. */
    private static final int INPUT_COLOR = 0xFFD8D8D8;

    /** How far a connection bar reaches; the block's face is at 0.5. */
    private static final float REACH = 0.46F;
    /** A cut stub stops well short, which is what makes "blocked" legible. */
    private static final float CUT_REACH = 0.16F;
    private static final float BAR_RADIUS = 0.055F;

    private ConnectionBars() {
    }

    static void bars(Slot slot, Neighbours neighbours, List<Box> out) {
        for (Direction direction : Direction.values()) {
            boolean cut = slot.forcedOff.contains(direction);
            boolean pinned = slot.forcedOn.contains(direction);
            if (cut) {
                out.add(bar(slot, direction, CUT_REACH, CUT_COLOR));
                continue;
            }
            boolean emits = PowerSolver.emitsToward(slot.type, slot.facing, direction, pinned, false);
            if (pinned) {
                // Pinned sides are shown whatever is there: the pin is the feature, and a bar that
                // only appeared once a neighbour existed would make "I pinned this" unverifiable.
                out.add(bar(slot, direction, REACH, PIN_COLOR));
            } else if (emits && neighbours.holdsComponent(direction)) {
                out.add(bar(slot, direction, REACH, wireColor(slot)));
            } else if (slot.type.isDriven() && direction == slot.facing) {
                // The input side, marked so a repeater's orientation is readable without guessing.
                out.add(bar(slot, direction, REACH * 0.6F, INPUT_COLOR));
            }
        }
    }

    /** The colour a connection bar takes from the component it leaves. */
    private static int wireColor(Slot slot) {
        if (slot.type == com.jiangyuefengyu.redstonecircuit.data.ComponentType.DUST) {
            return InnerComponentModel.dustColor(slot.power);
        }
        return slot.power > 0 ? 0xFFFF5030 : 0xFF7A4A3A;
    }

    /**
     * One bar reaching from the component towards a face of the block.
     *
     * <p>Built in component space, so a bar towards "north" is expressed as the component-space axis
     * that north maps to; the renderer applies the frame afterwards and never sees a special case.
     */
    private static Box bar(Slot slot, Direction direction, float reach, int colour) {
        InnerComponentModel.Basis basis = InnerComponentModel.Basis.of(slot.facing);
        float[] axis = basis.axis(direction);
        // The component sits on the block's floor, so a bar lies just above it.
        float floor = -0.40F + 0.01F;
        // The axis runs along exactly one of a/b/c; the other two keep the bar thin. Written out
        // rather than computed from |axis| so the intent survives a reader.
        float alongA = Math.abs(axis[0]) > 0.5F ? reach / 2 : BAR_RADIUS;
        float alongB = Math.abs(axis[1]) > 0.5F ? reach / 2 : BAR_RADIUS;
        float alongC = Math.abs(axis[2]) > 0.5F ? reach / 2 : BAR_RADIUS;
        return new Box(axis[0] * reach / 2, floor + axis[1] * reach / 2, axis[2] * reach / 2,
                alongA, alongB, alongC, colour);
    }
}

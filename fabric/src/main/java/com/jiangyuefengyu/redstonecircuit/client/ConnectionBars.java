package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Box;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Neighbours;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.Direction;

/**
 * The lines that show which sides of a component inside a block are connected, and what the wrench has done
 * to that routing.
 *
 * <p>Two pieces of inner wire look identical however they are routed, so the three wrench states are drawn
 * apart: connected - a line of wire reaching the block's face; pinned - a green marker at that face and no
 * line; cut - nothing at all. The pin sits at the block's face, clear of the model, and which sides are
 * drawn comes from {@link PowerSolver} rather than from a second set of rules.
 */
final class ConnectionBars {

    /** The marker on a side the wrench pinned. */
    private static final int PIN_COLOR = 0xFF35E05A;
    /** The side a driven component reads from. */
    private static final int INPUT_COLOR = 0xFFD8D8D8;

    /** How far a connection line reaches; the block's face is at 0.5. */
    private static final float REACH = 0.46F;
    private static final float BAR_RADIUS = 0.055F;

    /** How far out the pin sits - right at the face - and how big the mark is. */
    private static final float PIN_REACH = 0.44F;
    private static final float PIN_HALF = 0.06F;

    /**
     * The plane a wire's lines sit on: vanilla's wire model lies right on the floor of its block, so a line
     * drawn at the usual height would float above it.
     */
    private static final float WIRE_FLOOR = -0.44F;

    /**
     * The plane a pin sits on over everything except a wire: above the two-pixel slab vanilla gives a repeater
     * and a comparator, so the mark is clear of the model. A wire is flat and keeps its pin on WIRE_FLOOR.
     */
    private static final float PIN_FLOOR = -0.26F;

    private ConnectionBars() {
    }

    static void bars(Slot slot, Neighbours neighbours, List<Box> out) {
        for (Direction direction : Direction.values()) {
            if (slot.isClosed(direction)) {
                // A cut side draws nothing: no line is what "not connected" looks like, for a cut and for a locked-out side.
                continue;
            }
            if (slot.isOpen(direction)) {
                // A pinned side shows its marker whatever is beside it: a marker that needed a neighbour would make the pin invisible.
                out.add(mark(slot, direction, PIN_COLOR));
                continue;
            }
            boolean emits = PowerSolver.emitsToward(slot, direction);
            if (emits && neighbours.holdsComponent(direction)) {
                // Vanilla draws a wire's flat lines itself, and has no wire that runs up or down, so only vertical connections are ours.
                if (!slot.type.isWire() || direction.getAxis() == Direction.Axis.Y) {
                    out.add(line(slot, direction, REACH, wireColor(slot)));
                }
            } else if (slot.type.hasInputSide() && direction == slot.facing) {
                // The side a diode reads from gets a marker rather than a line: a line means a connection, and this side is where the component listens.
                out.add(mark(slot, direction, INPUT_COLOR));
            }
        }
    }

    /** The colour a connection line takes from the component it leaves. */
    private static int wireColor(Slot slot) {
        if (slot.type.isWire()) {
            return InnerComponentModel.wireColor(slot.type, slot.power);
        }
        return slot.power > 0 ? 0xFFFF5030 : 0xFF7A4A3A;
    }

    /**
     * A length of wire reaching from the component towards a face of the block. Built in component space, so
     * a line towards "north" is the component-space axis north maps to; the renderer applies the frame after.
     */
    private static Box line(Slot slot, Direction direction, float reach, int colour) {
        float floor = (slot.type.isWire() ? WIRE_FLOOR : InnerComponentModel.FLOOR) + 0.01F;
        return segment(slot, direction, reach / 2, reach / 2, BAR_RADIUS, colour, true, floor);
    }

    /**
     * A marker at the block's face on {@code direction} - the lock's pin and the side a diode reads from.
     * Solid rather than textured, because the wire art would say "there is a line here".
     */
    private static Box mark(Slot slot, Direction direction, int colour) {
        float floor = (slot.type.isWire() ? WIRE_FLOOR : PIN_FLOOR) + 0.01F;
        return segment(slot, direction, PIN_REACH, PIN_HALF, PIN_HALF, colour, false, floor);
    }

    /**
     * One axis-aligned piece of a line, {@code centre} out from the component along {@code direction}: the
     * axis runs along exactly one of a/b/c and the other two keep the piece thin.
     */
    private static Box segment(Slot slot, Direction direction, float centre, float half, float radius,
                               int colour, boolean textured, float floor) {
        InnerComponentModel.Basis basis = InnerComponentModel.Basis.of(slot);
        float[] axis = basis.axis(direction);
        float alongA = Math.abs(axis[0]) > 0.5F ? half : radius;
        float alongB = Math.abs(axis[1]) > 0.5F ? half : radius;
        float alongC = Math.abs(axis[2]) > 0.5F ? half : radius;
        float a = axis[0] * centre;
        float b = floor + axis[1] * centre;
        float c = axis[2] * centre;
        return textured ? Box.wire(a, b, c, alongA, alongB, alongC, colour)
                : new Box(a, b, c, alongA, alongB, alongC, colour);
    }
}

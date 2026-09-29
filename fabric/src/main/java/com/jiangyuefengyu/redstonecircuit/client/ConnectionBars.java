package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Box;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Neighbours;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.Direction;

/**
 * The lines that show which sides of a component inside a block are connected, and what the wrench has
 * done to that routing.
 *
 * <h2>Why this is worth drawing</h2>
 * Two pieces of inner wire look identical however they are routed, and the whole point of the wrench
 * (R7) is to change that routing. So the drawing has to show it, and it has to show the difference
 * between the three states the wrench walks through:
 *
 * <pre>
 *   connected   a line of wire, in the component's own colour, reaching the block's face
 *   pinned      a green marker at that face, and no line at all
 *   cut         nothing at all - the absence of a line is what "not connected" looks like
 * </pre>
 *
 * <p>Both of the wrench's states are deliberately shown without a line. The line <em>is</em> the
 * connection, so no line already means no connection, and a mark would only be a second thing to read -
 * which matters most for a lock, since a lock cuts every side it does not name, and marking those would
 * surround a component with marks for lines that were never there.
 *
 * <p>The pin marker rides at the block's face rather than in the middle of the line. Put in the middle
 * it landed on the component itself - a repeater is a slab two pixels tall right across the block, with
 * its torches a little further out along the very axis the marker sits on - so it read as part of the
 * model rather than as a mark on the side. At the face, clear of the slab, it marks the connection
 * point, which is what it is about.
 *
 * <p>The line is drawn with the game's own wire art ({@link Box#textured}), so a connection reads as
 * redstone rather than as a coloured stick, and only its colour is ours.
 *
 * <p>Which sides those are comes from {@link PowerSolver} itself rather than from a second set of
 * rules, so the drawing cannot disagree with the solver about where a repeater points. The one thing
 * the solver cannot answer is what is in the neighbouring blocks, so that is passed in.
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
     * The plane a wire's lines sit on, a little lower than the other components': vanilla's wire model
     * lies right on the floor of its block, so a line drawn at the usual height would float above it.
     */
    private static final float WIRE_FLOOR = -0.44F;

    /**
     * The plane a pin sits on over everything except a wire.
     *
     * <p>Above the two-pixel slab vanilla gives a repeater and a comparator, so the mark is clear of the
     * model instead of lying on it. A wire has no such slab and is flat anyway, so its pin stays down on
     * the wire's own plane.
     */
    private static final float PIN_FLOOR = -0.26F;

    private ConnectionBars() {
    }

    static void bars(Slot slot, Neighbours neighbours, List<Box> out) {
        for (Direction direction : Direction.values()) {
            if (slot.isClosed(direction)) {
                // A cut side draws nothing: no line is exactly what "not connected" looks like, and an
                // extra mark would only be a second thing to read. This covers both an explicit cut and
                // a side a lock left out - the same thing to the signal, and to the eye.
                continue;
            }
            if (slot.isOpen(direction)) {
                // A locked side is shown by its marker alone. It is drawn whatever is beside it: the
                // lock is the feature, and a mark that only appeared once a neighbour existed would make
                // "I locked this" unverifiable.
                out.add(mark(slot, direction, PIN_COLOR));
                continue;
            }
            boolean emits = PowerSolver.emitsToward(slot, direction);
            if (emits && neighbours.holdsComponent(direction)) {
                // A wire's flat lines are vanilla's own model's business now - a redstone wire really is
                // drawn out of one dot plus one line per connected side - so only its vertical
                // connections are left for us; vanilla has no wire that runs up or down.
                if (!slot.type.isWire() || direction.getAxis() == Direction.Axis.Y) {
                    out.add(line(slot, direction, REACH, wireColor(slot)));
                }
            } else if (slot.type.isDriven() && direction == slot.facing) {
                // The side a diode reads from. A marker rather than a line, for the same reason the
                // lock has one: a line now means a connection, and this side is not connected to
                // anything - it is where the component listens. Drawn as a line it looked like the
                // repeater was pushing a signal into the block behind it.
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
     * A length of wire reaching from the component towards a face of the block.
     *
     * <p>Built in component space, so a line towards "north" is expressed as the component-space axis
     * that north maps to; the renderer applies the frame afterwards and never sees a special case.
     */
    private static Box line(Slot slot, Direction direction, float reach, int colour) {
        float floor = (slot.type.isWire() ? WIRE_FLOOR : InnerComponentModel.FLOOR) + 0.01F;
        return segment(slot, direction, reach / 2, reach / 2, BAR_RADIUS, colour, true, floor);
    }

    /**
     * A marker at the block's face on {@code direction}.
     *
     * <p>This is how everything we annotate is drawn: the lock's pin and the side a diode reads from.
     * Solid rather than textured, because it is a mark and not a piece of wire - the wire art on it
     * would say "there is a line here", which is exactly what these two states do not have.
     */
    private static Box mark(Slot slot, Direction direction, int colour) {
        float floor = (slot.type.isWire() ? WIRE_FLOOR : PIN_FLOOR) + 0.01F;
        return segment(slot, direction, PIN_REACH, PIN_HALF, PIN_HALF, colour, false, floor);
    }

    /**
     * One axis-aligned piece of a line, {@code centre} out from the component along {@code direction}.
     *
     * <p>The axis runs along exactly one of a/b/c and the other two keep the piece thin. Written out
     * rather than computed from {@code |axis|} so the intent survives a reader.
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

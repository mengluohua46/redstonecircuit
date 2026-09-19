package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;

/**
 * The shape of the component that lives inside a host block, as a handful of boxes.
 *
 * <h2>Coordinates</h2>
 * Everything here is in "component space", measured in blocks with the block's centre at the origin:
 *
 * <pre>
 *   a  - sideways, along {@code right}
 *   b  - upwards, along {@code up}
 *   c  - forwards, along the component's {@code facing} (the side it reads from)
 * </pre>
 *
 * <p>Keeping the model in this frame rather than in world coordinates is what lets a repeater point
 * anywhere: the renderer turns this frame into the block's axes with {@link Basis} once, and the model
 * itself never has to know whether it ended up facing north or up. It also means the shapes can be
 * checked without a game, which matters because a wrong shape is invisible in a headless test and
 * obvious in the wrong way in game.
 *
 * <h2>Why the connection bars matter</h2>
 * A wire inside a block looks the same whichever way it is routed, so without something showing the
 * routing, "adjust it with the wrench" (R7) would have no visible result. {@link ConnectionBars} draws
 * one bar per side the component actually talks to, and marks the sides the wrench has pinned or cut.
 */
public final class InnerComponentModel {

    /** Half-extent of the flat plates every component is built from. */
    private static final float THIN = 0.055F;

    /** How far the pieces sit from the block's centre; 0.5 would be the block's face. */
    private static final float FLOOR = -0.40F;

    private InnerComponentModel() {
    }

    /**
     * The block's axes, as the component sees them.
     *
     * @param right {@code a} axis, a unit vector along a block axis
     * @param up {@code b} axis
     * @param forward {@code c} axis: the direction the component reads from
     */
    public record Basis(float[] right, float[] up, float[] forward) {

        /**
         * Builds the frame for a component facing {@code facing}.
         *
         * <p>For a component mounted on the top or bottom of a block its "up" cannot be up, so north is
         * used instead - the same choice vanilla makes for a repeater lying on its back.
         */
        public static Basis of(Direction facing) {
            float[] forward = unit(facing);
            float[] up = facing.getAxis() == Direction.Axis.Y
                    ? unit(Direction.NORTH)
                    : new float[] { 0, 1, 0 };
            // right = forward x up, which keeps the frame right-handed.
            float[] right = {
                    forward[1] * up[2] - forward[2] * up[1],
                    forward[2] * up[0] - forward[0] * up[2],
                    forward[0] * up[1] - forward[1] * up[0],
            };
            return new Basis(right, up, forward);
        }

        private static float[] unit(Direction direction) {
            return new float[] { direction.getStepX(), direction.getStepY(), direction.getStepZ() };
        }

        /** Maps a point of component space to block-local 0..1 coordinates, written into {@code out}. */
        public void point(float a, float b, float c, float[] out) {
            out[0] = 0.5F + a * right[0] + b * up[0] + c * forward[0];
            out[1] = 0.5F + a * right[1] + b * up[1] + c * forward[1];
            out[2] = 0.5F + a * right[2] + b * up[2] + c * forward[2];
        }

        /**
         * The same mapping for a direction given in block coordinates.
         *
         * <p>Used to turn "north" into the component-space axis it corresponds to, which is how the
         * connection bars are laid out without a second set of rules.
         */
        public float[] axis(Direction direction) {
            float[] unit = unit(direction);
            return new float[] {
                    dot(unit, right), dot(unit, up), dot(unit, forward),
            };
        }

        private static float dot(float[] a, float[] b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        }
    }

    /** One box: its centre and half-extents in component space, plus its colour. */
    public record Box(float a, float b, float c, float ha, float hb, float hc, int argb) {
    }

    /** Which of the six sides of this block hold another component; used to place connection bars. */
    @FunctionalInterface
    public interface Neighbours {
        /** True when the block on that side holds redstone of its own. */
        boolean holdsComponent(Direction direction);
    }

    /** The eight corners of one box, as signs -1/+1 in component space. */
    private static final int[][] SIGNS = {
            { -1, -1, -1 }, { 1, -1, -1 }, { 1, -1, 1 }, { -1, -1, 1 },
            { -1, 1, -1 }, { 1, 1, -1 }, { 1, 1, 1 }, { -1, 1, 1 },
    };

    /**
     * The six faces of a box, as indices into {@link #SIGNS}, wound anticlockwise seen from outside.
     *
     * <p>The renderer relies on this winding for back-face culling, so it is spelled out rather than
     * derived: face order is down, up, north, south, west, east in component space.
     */
    private static final int[][] FACES = {
            { 0, 3, 2, 1 },
            { 4, 5, 6, 7 },
            { 0, 1, 5, 4 },
            { 2, 3, 7, 6 },
            { 3, 0, 4, 7 },
            { 1, 2, 6, 5 },
    };

    /**
     * Appends the boxes for one component to {@code out}.
     *
     * <p>Connection bars come first so the component's own shape is drawn over them. Every box is
     * clamped into the block on the way out: a bar reaching towards the block's floor has nowhere to go
     * once it gets there, and without the clamp it would poke into the block below - which is a bug that
     * only ever shows up in game.
     */
    public static void boxes(Slot slot, Neighbours neighbours, List<Box> out) {
        int first = out.size();
        ConnectionBars.bars(slot, neighbours, out);
        switch (slot.type) {
            case DUST, SUPERCONDUCTOR -> dust(slot, out);
            case REPEATER -> repeater(slot, out);
            case COMPARATOR -> comparator(slot, out);
            case TORCH -> torch(slot, out);
            case LEVER, BUTTON -> lever(slot, out);
            case PRESSURE_PLATE -> out.add(new Box(0, FLOOR, 0, 0.4F, 0.04F, 0.4F, 0xFF8B1A1A));
        }
        for (int i = first; i < out.size(); i++) {
            out.set(i, clampToBlock(out.get(i)));
        }
    }

    /** The furthest a corner may sit from the block's centre: the block's faces are at 0.5. */
    private static final float LIMIT = 0.4995F;

    /** Shrinks a box so that it stays inside the block, keeping it centred on what is left. */
    static Box clampToBlock(Box box) {
        float[] centre = { box.a(), box.b(), box.c() };
        float[] half = { box.ha(), box.hb(), box.hc() };
        for (int axis = 0; axis < 3; axis++) {
            float low = Math.max(-LIMIT, centre[axis] - half[axis]);
            float high = Math.min(LIMIT, centre[axis] + half[axis]);
            if (high < low) {
                // Fully outside: collapse it to the face rather than letting it escape.
                low = Math.max(-LIMIT, Math.min(LIMIT, centre[axis]));
                high = low;
            }
            centre[axis] = (low + high) / 2;
            half[axis] = (high - low) / 2;
        }
        return new Box(centre[0], centre[1], centre[2], half[0], half[1], half[2], box.argb());
    }

    // --------------------------------------------------------------- shapes --

    /** A wire: a flat cross at the block's floor, so the connection bars read as leaving it. */
    private static void dust(Slot slot, List<Box> out) {
        int colour = dustColor(slot.power);
        out.add(new Box(0, FLOOR, 0, 0.16F, THIN, 0.34F, colour));
        out.add(new Box(0, FLOOR, 0, 0.34F, THIN, 0.16F, colour));
    }

    /**
     * A repeater: a slab with a torch at each end, and one pip per delay setting.
     *
     * <p>The pips are how the delay becomes visible from outside the block; vanilla shows it with the
     * spacing of the two torches, which does not read at this size.
     */
    private static void repeater(Slot slot, List<Box> out) {
        out.add(new Box(0, FLOOR, 0, 0.24F, 0.05F, 0.38F, 0xFF9A9A9A));
        int lit = slot.power > 0 ? 0xFFFF2020 : 0xFF5A0F0F;
        out.add(torchBody(0, FLOOR + 0.1F, -0.22F, lit));
        out.add(torchBody(0, FLOOR + 0.1F, 0.22F, lit));
        for (int i = 0; i < delay(slot); i++) {
            out.add(new Box(0, FLOOR + 0.1F, -0.12F + i * 0.08F, 0.16F, 0.02F, 0.02F, 0xFFE8E8E8));
        }
    }

    /** A comparator: a repeater's slab with the two side torches of compare/subtract mode. */
    private static void comparator(Slot slot, List<Box> out) {
        out.add(new Box(0, FLOOR, 0, 0.24F, 0.05F, 0.38F, 0xFF9A9A9A));
        int lit = slot.power > 0 ? 0xFFFF2020 : 0xFF5A0F0F;
        out.add(torchBody(0, FLOOR + 0.1F, 0.24F, lit));
        out.add(torchBody(0, FLOOR + 0.1F, -0.24F, lit));
        if (slot.mode == ComparatorMode.SUBTRACT) {
            // Subtract mode gets a bar where compare mode has nothing, so the two are told apart.
            out.add(new Box(0, FLOOR + 0.08F, 0, 0.2F, 0.03F, 0.03F, 0xFFE8E8E8));
        } else {
            out.add(new Box(0, FLOOR + 0.08F, 0, 0.06F, 0.03F, 0.06F, 0xFFE8E8E8));
        }
    }

    /** A torch: a post with a bulb on top, lit or dark. */
    private static void torch(Slot slot, List<Box> out) {
        out.add(torchBody(0, -0.05F, 0, slot.power > 0 ? 0xFFFF3030 : 0xFF4A1010));
    }

    /** A lever or button: a mount and a handle that leans the way the switch is thrown. */
    private static void lever(Slot slot, List<Box> out) {
        out.add(new Box(0, FLOOR, 0, 0.26F, 0.04F, 0.26F, 0xFF7A7A7A));
        out.add(new Box(0, FLOOR + 0.06F, 0, 0.1F, 0.05F, 0.1F, 0xFF3A3A3A));
        int handle = slot.powered ? 0xFFFF4040 : 0xFF8A5A5A;
        // The handle leans towards the side the switch reads from when it is on.
        out.add(new Box(0, -0.22F, slot.powered ? 0.16F : -0.02F, 0.06F, 0.12F, 0.06F, handle));
    }

    /** The post-and-bulb every torch-shaped part is made of. */
    private static Box torchBody(float a, float b, float c, int colour) {
        return new Box(a, b, c, 0.06F, 0.11F, 0.06F, colour);
    }

    private static int delay(Slot slot) {
        return Math.max(1, Math.min(4, slot.delay));
    }

    /**
     * The colour of a piece of wire at a given strength: red for dust, orange for the superconductor.
     *
     * <p>Both keep vanilla's "dark when idle, hot at fifteen" brightness ramp, so a run of either is
     * readable at a glance; the two materials are told apart by hue rather than by shape, which matters
     * because they look identical otherwise.
     */
    public static int wireColor(ComponentType type, int power) {
        return type == ComponentType.SUPERCONDUCTOR ? superconductorColor(power) : dustColor(power);
    }

    /**
     * The colour of ordinary wire at a given strength: exactly vanilla's own ramp, so inner dust looks
     * like the dust outside the block.
     */
    public static int dustColor(int power) {
        float strength = strength(power);
        return argb(
                strength * 0.6F + 0.4F,
                Math.max(0.0F, strength * strength * 0.7F - 0.5F),
                Math.max(0.0F, strength * strength * 0.6F - 0.7F));
    }

    /**
     * The same ramp in orange: the vanilla brightness, with the green channel carried along it instead
     * of only appearing at full strength.
     *
     * <p>Vanilla's own green channel is zero for most of the range, which is what makes a wire red;
     * giving orange a proportional share of the brightness keeps it orange when it is idle as well as
     * when it is hot, so the two materials never look alike.
     */
    public static int superconductorColor(int power) {
        float strength = strength(power);
        float brightness = strength * 0.6F + 0.4F;
        return argb(brightness, brightness * 0.55F + strength * strength * 0.2F, brightness * 0.05F);
    }

    private static float strength(int power) {
        return Math.max(0, Math.min(15, power)) / 15.0F;
    }

    private static int argb(float red, float green, float blue) {
        return 0xFF000000 | (channel(red) << 16) | (channel(green) << 8) | channel(blue);
    }

    private static int channel(float value) {
        return (int) Math.min(255.0F, Math.max(0.0F, value) * 255.0F);
    }

    // ------------------------------------------------------------- geometry --

    /** The eight corners of a box in component space, each as {@code {a, b, c}}. */
    public static float[][] corners(Box box) {
        float[][] out = new float[8][3];
        for (int i = 0; i < 8; i++) {
            out[i][0] = box.a() + SIGNS[i][0] * box.ha();
            out[i][1] = box.b() + SIGNS[i][1] * box.hb();
            out[i][2] = box.c() + SIGNS[i][2] * box.hc();
        }
        return out;
    }

    /** The four corner indices of one of a box's faces; face order is that of {@link #FACES}. */
    public static int[] face(int index) {
        return FACES[index];
    }

    /** How many faces a box has. */
    public static int faceCount() {
        return FACES.length;
    }
}

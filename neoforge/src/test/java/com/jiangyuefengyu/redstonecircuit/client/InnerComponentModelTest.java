package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Basis;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Box;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Neighbours;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the geometry of the component drawn inside a block.
 *
 * <p>This is the part of the rendering that can be checked without a game, and the part where a mistake
 * is worst: a box that leaves the block pokes through its neighbours, a basis that is not orthogonal
 * draws a repeater as a smear, and a missing connection bar makes the wrench look broken. Everything
 * else about the renderer is blending and depth, which a headless test cannot judge - so the arithmetic
 * is pinned here and the rest is left to the eye.
 */
class InnerComponentModelTest {

    private static final Neighbours NOTHING = direction -> false;
    private static final Neighbours EVERYTHING = direction -> true;

    private static List<Box> boxes(Slot slot, Neighbours neighbours) {
        List<Box> out = new ArrayList<>();
        InnerComponentModel.boxes(slot, neighbours, out);
        return out;
    }

    private static Slot slot(ComponentType type) {
        Slot slot = new Slot(type);
        slot.power = 15;
        return slot;
    }

    @Test
    @DisplayName("every component draws something, in every orientation")
    void everyComponentAndFacingDrawsSomething() {
        for (ComponentType type : ComponentType.values()) {
            for (Direction facing : Direction.values()) {
                Slot slot = new Slot(type);
                slot.facing = facing;
                slot.power = type.initialPower();
                List<Box> out = boxes(slot, EVERYTHING);
                assertTrue(out.size() > 0, type + " facing " + facing + " must draw something");
            }
        }
    }

    /** The block's faces are at 0.5 from its centre: a box past one is drawn inside the neighbour. */
    @Test
    @DisplayName("no box leaves the block it is drawn in")
    void boxesStayInsideTheBlock() {
        for (ComponentType type : ComponentType.values()) {
            for (Direction facing : Direction.values()) {
                Slot slot = new Slot(type);
                slot.facing = facing;
                slot.delay = 4;
                slot.mode = ComparatorMode.SUBTRACT;
                slot.power = 15;
                slot.setConnection(Direction.NORTH, ConnectionState.ON);
                slot.setConnection(Direction.UP, ConnectionState.OFF);
                for (Box box : boxes(slot, EVERYTHING)) {
                    Basis basis = Basis.of(facing);
                    float[] mapped = new float[3];
                    for (float[] corner : InnerComponentModel.corners(box)) {
                        basis.point(corner[0], corner[1], corner[2], mapped);
                        for (int axis = 0; axis < 3; axis++) {
                            assertTrue(mapped[axis] >= -0.001F && mapped[axis] <= 1.001F,
                                    type + " facing " + facing + " leaves the block on axis " + axis
                                            + ": " + mapped[axis]);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the basis is orthonormal and follows the facing")
    void basisIsOrthonormal() {
        for (Direction facing : Direction.values()) {
            Basis basis = Basis.of(facing);
            assertNear(1.0F, length(basis.forward()), "forward must be a unit vector");
            assertNear(1.0F, length(basis.up()), "up must be a unit vector");
            assertNear(1.0F, length(basis.right()), "right must be a unit vector");
            assertNear(0.0F, dot(basis.forward(), basis.up()), "forward and up must be perpendicular");
            assertNear(0.0F, dot(basis.forward(), basis.right()), "forward and right must be perpendicular");
            assertNear(0.0F, dot(basis.up(), basis.right()), "up and right must be perpendicular");

            // The frame's forward really is the facing, expressed in component space.
            float[] axis = basis.axis(facing);
            assertNear(0.0F, axis[0], "facing on the a axis");
            assertNear(0.0F, axis[1], "facing on the b axis");
            assertNear(1.0F, axis[2], "facing is the c axis");
        }
    }

    @Test
    @DisplayName("the block's centre maps to the component-space origin")
    void basisMapsTheCentre() {
        Basis basis = Basis.of(Direction.NORTH);
        float[] out = new float[3];
        basis.point(0, 0, 0, out);
        assertNear(0.5F, out[0], "x");
        assertNear(0.5F, out[1], "y");
        assertNear(0.5F, out[2], "z");
    }

    // -------------------------------------------------------------- wire --

    @Test
    @DisplayName("wire colour follows vanilla's ramp: dark when idle, hot when full")
    void dustColourRamp() {
        int idle = InnerComponentModel.dustColor(0);
        int full = InnerComponentModel.dustColor(15);
        int half = InnerComponentModel.dustColor(8);

        assertEquals(0xFF000000 | (0x66 << 16), idle, "an unpowered wire is a dark red");
        assertTrue((full & 0xFF0000) >> 16 > (half & 0xFF0000) >> 16, "brighter as it gets stronger");
        assertTrue((half & 0xFF0000) >> 16 > (idle & 0xFF0000) >> 16);
        assertTrue((full & 0xFF0000) >> 16 >= 250, "and white hot at fifteen");
    }

    /**
     * The two wire materials must never be mistaken for each other, at any strength: dust is red
     * throughout and the superconductor is orange throughout.
     */
    @Test
    @DisplayName("superconducting wire is orange at every strength, and never red")
    void superconductorColourIsOrange() {
        for (int power = 0; power <= 15; power++) {
            int colour = InnerComponentModel.superconductorColor(power);
            int red = (colour >> 16) & 0xFF;
            int green = (colour >> 8) & 0xFF;
            int blue = colour & 0xFF;

            assertTrue(green > 0, "power " + power + " must not be a pure red: " + Integer.toHexString(colour));
            assertTrue(green < red, "power " + power + " must stay red-dominant, not yellow");
            assertTrue(green * 2 > red, "power " + power + " needs enough green to read as orange");
            assertTrue(blue < green, "power " + power + " must not drift towards white");
        }
        assertTrue(((InnerComponentModel.superconductorColor(15) >> 8) & 0xFF)
                        > ((InnerComponentModel.superconductorColor(0) >> 8) & 0xFF),
                "and brighter as the signal rises");
    }

    /**
     * The inner wire is vanilla's greyscale dust art tinted; the placed wire's art already carries the orange.
     * The tint must be that exact orange at full strength, or the two do not match side by side.
     */
    @Test
    @DisplayName("the inner wire's orange is the orange the placed wire's art already carries")
    void innerOrangeMatchesThePlacedArt() {
        assertEquals(0xFFFF8C00, InnerComponentModel.superconductorColor(15) | 0xFF000000,
                "full strength must be exactly the art's orange");
        assertEquals(0x66, (InnerComponentModel.superconductorColor(0) >> 16) & 0xFF,
                "and idle wire shares the placed wire's four-tenths brightness");
    }

    /**
     * The placed wire's tint is brightness only - its colour is in the texture - so it has to be a grey, or
     * the two multiply into something dark and muddy.
     */
    @Test
    @DisplayName("the placed wire's tint is a grey brightness ramp")
    void wireBrightnessIsGrey() {
        for (int power = 0; power <= 15; power++) {
            int tint = InnerComponentModel.wireBrightness(power);
            int red = (tint >> 16) & 0xFF;
            int green = (tint >> 8) & 0xFF;
            int blue = tint & 0xFF;

            assertEquals(red, green, "power " + power + " must be grey");
            assertEquals(green, blue, "power " + power + " must be grey");
        }
        assertEquals(0x66, InnerComponentModel.wireBrightness(0) & 0xFF,
                "idle wire is four tenths bright, exactly like vanilla's");
        assertEquals(0xFF, InnerComponentModel.wireBrightness(15) & 0xFF, "and full at fifteen");
    }

    @Test
    @DisplayName("a wire's flat lines come from vanilla's model, so only its vertical sides get a bar")
    void wireBarsAreVerticalOnly() {
        // Since 1.1.0 a wire is drawn with vanilla's own redstone-wire model, which already draws a line
        // towards every side it connects to. What vanilla has no model for is a wire that runs up or
        // down - redstone wire on the ground cannot - so those two are the only bars left to us.
        Slot wire = slot(ComponentType.DUST);
        int alone = boxes(wire, NOTHING).size();
        int joined = boxes(wire, EVERYTHING).size();

        assertEquals(alone + 2, joined, "up and down, and nothing else");
    }

    @Test
    @DisplayName("a pin is a green mark at the block's face, and nothing else")
    void pinnedBarsAreDrawnWithoutANeighbour() {
        Slot wire = slot(ComponentType.DUST);
        wire.setConnection(Direction.EAST, ConnectionState.ON);
        List<Box> out = boxes(wire, NOTHING);

        List<Box> pins = out.stream().filter(box -> box.argb() == 0xFF35E05A).toList();
        assertEquals(1, pins.size(), "one marker per pinned side, and no line beside it");
        Box pin = pins.get(0);
        assertTrue(!pin.textured(), "the marker is a solid mark, not a piece of wire");
        assertTrue(pin.a() > 0.3F,
                "and it sits out at the block's face rather than on top of the component");
    }

    @Test
    @DisplayName("a pin over a repeater clears the slab vanilla draws across the whole block")
    void pinOverADiodeSitsAboveItsSlab() {
        // Vanilla's repeater is a two-pixel slab right across the block, so a marker down at the
        // component's own level lands on it and reads as part of the model.
        Slot repeater = slot(ComponentType.REPEATER);
        repeater.setConnection(Direction.EAST, ConnectionState.ON);
        Box pin = boxes(repeater, NOTHING).stream()
                .filter(box -> box.argb() == 0xFF35E05A)
                .findFirst()
                .orElseThrow(() -> new AssertionError("the pinned side needs its marker"));

        float slabTop = -0.5F + 2F / 16F;
        assertTrue(pin.b() - pin.hb() > slabTop,
                "the marker's lowest corner has to be above the slab, not buried in or lying on it");
    }

    @Test
    @DisplayName("a cut side draws nothing at all: no line is what not connected looks like")
    void cutSidesDrawNothing() {
        // A lever rather than a wire: a wire's flat lines are vanilla's business now, so the loss of a
        // line can only still be seen on a component whose lines we draw ourselves.
        Slot lever = slot(ComponentType.LEVER);
        int uncut = boxes(lever, EVERYTHING).size();
        lever.setConnection(Direction.EAST, ConnectionState.OFF);
        List<Box> withCut = boxes(lever, EVERYTHING);

        assertEquals(uncut - 1, withCut.size(), "the cut side simply loses its line");
        assertTrue(withCut.stream().noneMatch(box -> box.argb() == 0xFF000000),
                "and nothing is drawn in its place");
    }

    @Test
    @DisplayName("a lock leaves every side it did not name with no line, and no marker either")
    void impliedCutsDrawNothing() {
        // Alone, a lever contributes only its own shape: nothing is beside it, so no connection is drawn.
        int shape = boxes(slot(ComponentType.LEVER), NOTHING).size();

        Slot lever = slot(ComponentType.LEVER);
        lever.setConnection(Direction.EAST, ConnectionState.ON);
        List<Box> out = boxes(lever, EVERYTHING);

        assertEquals(shape + 1, out.size(),
                "the pinned side's marker, and nothing for the five sides the lock cut");
    }

    @Test
    @DisplayName("a diode marks the side it reads from, with a marker rather than a line")
    void diodesMarkTheirInput() {
        Slot repeater = slot(ComponentType.REPEATER);
        repeater.facing = Direction.WEST;
        List<Box> out = boxes(repeater, NOTHING);

        List<Box> inputs = out.stream().filter(box -> box.argb() == 0xFFD8D8D8).toList();
        assertEquals(1, inputs.size(), "one mark on the side it reads from");
        assertTrue(!inputs.get(0).textured(),
                "and it is a mark, not a line: a line would say the component is connected there,"
                        + " which it is not");
    }

    @Test
    @DisplayName("a repeater's delay is drawn as one pip per setting")
    void repeaterDelayIsVisible() {
        Slot fast = slot(ComponentType.REPEATER);
        fast.delay = 1;
        Slot slow = slot(ComponentType.REPEATER);
        slow.delay = 4;

        assertEquals(3, boxes(slow, NOTHING).size() - boxes(fast, NOTHING).size(),
                "three extra pips between the fastest and the slowest setting");
    }

    @Test
    @DisplayName("a comparator's two modes do not look the same")
    void comparatorModesDiffer() {
        Slot compare = slot(ComponentType.COMPARATOR);
        compare.mode = ComparatorMode.COMPARE;
        Slot subtract = slot(ComponentType.COMPARATOR);
        subtract.mode = ComparatorMode.SUBTRACT;

        assertEquals(boxes(compare, NOTHING).size(), boxes(subtract, NOTHING).size(),
                "the same number of boxes");
        assertNotEquals(boxes(compare, NOTHING), boxes(subtract, NOTHING),
                "but compare draws a small pip where subtract draws a long bar");
    }

    @Test
    @DisplayName("a lit torch and a dark one are different colours")
    void torchColourFollowsPower() {
        Slot lit = slot(ComponentType.TORCH);
        lit.power = 15;
        Slot dark = slot(ComponentType.TORCH);
        dark.power = 0;

        assertNotEquals(boxes(lit, NOTHING), boxes(dark, NOTHING));
    }

    @Test
    @DisplayName("a box has six faces, each naming four distinct corners")
    void facesAreWellFormed() {
        for (int face = 0; face < InnerComponentModel.faceCount(); face++) {
            int[] indices = InnerComponentModel.face(face);
            assertEquals(4, indices.length, "a quad has four corners");
            assertEquals(4, java.util.Arrays.stream(indices).distinct().count(),
                    "and they must be four different corners");
        }
        assertEquals(6, InnerComponentModel.faceCount());
    }

    private static void assertNear(float expected, float actual, String what) {
        assertTrue(Math.abs(expected - actual) < 1.0E-4F,
                what + ": expected " + expected + " but was " + actual);
    }

    private static float length(float[] vector) {
        return (float) Math.sqrt(dot(vector, vector));
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}

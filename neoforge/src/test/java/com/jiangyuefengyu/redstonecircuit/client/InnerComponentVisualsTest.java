package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Neighbours;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentVisuals.Model;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentVisuals.Placement;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.AttachFace;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the mapping from a stored component onto vanilla's vocabulary: which vanilla state stands for a
 * component is where a mistake shows up as a visibly wrong thing - a repeater pointing backwards, a torch
 * on its side, a wire claiming connections it does not have.
 *
 * <p>Kept free of registries so it can be checked with no game running; the matrix half is left to the eye.
 */
class InnerComponentVisualsTest {

    private static final Neighbours NOTHING = direction -> false;
    private static final Neighbours EVERYTHING = direction -> true;

    private static Slot slot(ComponentType type, Direction facing) {
        Slot slot = new Slot(type);
        slot.facing = facing;
        slot.power = 15;
        return slot;
    }

    private static Placement place(Slot slot, Neighbours neighbours) {
        Placement placement = InnerComponentVisuals.placementFor(slot, neighbours);
        assertNotNull(placement, "every placeable component maps onto something");
        return placement;
    }

    // ------------------------------------------------------------------- wires --

    @Test
    @DisplayName("a wire draws a line only towards a side that is both live and occupied")
    void wireLinesNeedSignalAndNeighbour() {
        Slot slot = slot(ComponentType.DUST, Direction.NORTH);
        assertEquals(Set.of(), place(slot, NOTHING).wireSides(),
                "a lone wire is vanilla's dot, with no lines at all");
        assertEquals(EnumSet.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST),
                place(slot, EVERYTHING).wireSides(),
                "a wire touching four components draws four lines");
        assertTrue(place(slot, EVERYTHING).wireSides().stream()
                        .noneMatch(direction -> direction.getAxis() == Direction.Axis.Y),
                "vanilla's wire model has no vertical line, so up and down are never in this set");
    }

    @Test
    @DisplayName("a locked wire draws its lines only where the wrench pinned them")
    void lockedWireRoutingIsExplicit() {
        Slot slot = slot(ComponentType.SUPERCONDUCTOR, Direction.NORTH);
        slot.setConnection(Direction.EAST, ConnectionState.ON);

        Placement placement = place(slot, EVERYTHING);
        assertEquals(Model.WIRE, placement.model());
        assertEquals(EnumSet.of(Direction.EAST), placement.wireSides(),
                "a lock means 'this line and nothing else', so the other sides are cut");
    }

    // ------------------------------------------------------------------ diodes --

    @Test
    @DisplayName("a horizontal repeater hands its reading side to vanilla unchanged")
    void repeaterReadsFromTheSideVanillaCallsFacing() {
        // Vanilla's FACING is the side a repeater reads from, exactly what Slot#facing means: never flip it.
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Slot slot = slot(ComponentType.REPEATER, facing);
            slot.delay = 3;
            Placement placement = place(slot, EVERYTHING);
            assertEquals(Model.REPEATER, placement.model());
            assertEquals(facing, placement.facing(), "reads from " + facing);
            assertEquals(0F, placement.tilt(), "vanilla's variant table does the turning");
            assertEquals(3, placement.delay());
            assertTrue(placement.lit(), "power 15 means a lit repeater");
        }
    }

    @Test
    @DisplayName("a repeater on the floor or the ceiling lies on its back")
    void verticalRepeaterIsTilted() {
        // Vanilla's diode FACING is horizontal only, so these two cases are rotated here: the model reads south.
        Slot below = slot(ComponentType.REPEATER, Direction.DOWN);
        Placement down = place(below, EVERYTHING);
        assertEquals(Model.REPEATER, down.model());
        assertEquals(Direction.SOUTH, down.facing());
        assertEquals(90F, down.tilt(), "reading from below: south becomes down");

        Slot above = slot(ComponentType.REPEATER, Direction.UP);
        Placement up = place(above, EVERYTHING);
        assertEquals(Direction.SOUTH, up.facing());
        assertEquals(-90F, up.tilt(), "reading from above: south becomes up");
    }

    @Test
    @DisplayName("a locked repeater uses vanilla's own locked look")
    void lockedRepeaterLooksLocked() {
        Slot slot = slot(ComponentType.REPEATER, Direction.NORTH);
        assertTrue(!place(slot, EVERYTHING).locked(), "nothing is locked yet");
        slot.setConnection(Direction.EAST, ConnectionState.ON);
        assertTrue(place(slot, EVERYTHING).locked(),
                "the wrench locked a side, so the vanilla locked bar is drawn");
    }

    @Test
    @DisplayName("the three new repeaters draw their own models, at the setting that was set")
    void newRepeatersDrawTheirOwnModels() {
        ComponentType[] types = {
                ComponentType.RETAINING_REPEATER,
                ComponentType.DOUBLE_REPEATER,
                ComponentType.DOUBLE_RETAINING_REPEATER };
        Model[] models = {
                Model.RETAINING_REPEATER,
                Model.DOUBLE_REPEATER,
                Model.DOUBLE_RETAINING_REPEATER };

        for (int i = 0; i < types.length; i++) {
            String name = types[i].toString();
            Slot slot = slot(types[i], Direction.EAST);
            slot.delay = 2;
            Placement placement = place(slot, EVERYTHING);

            assertEquals(models[i], placement.model(), name + " must draw its own art, not a vanilla repeater");
            assertEquals(Direction.EAST, placement.facing(), name + " reads from the side vanilla calls facing");
            assertEquals(2, placement.delay(),
                    name + " shows the setting it was set to - the doubling is in the timing, not the look");
            assertTrue(placement.lit(), name + " at power 15 is lit");

            // The wrench locks these exactly as it locks a vanilla repeater.
            slot.setConnection(Direction.NORTH, ConnectionState.ON);
            assertTrue(place(slot, EVERYTHING).locked(), name + " uses vanilla's own locked look");
        }
    }

    @Test
    @DisplayName("a comparator carries its mode, and is never called locked")
    void comparatorMode() {
        Slot slot = slot(ComponentType.COMPARATOR, Direction.WEST);
        Placement compare = place(slot, EVERYTHING);
        assertEquals(Model.COMPARATOR, compare.model());
        assertEquals(Direction.WEST, compare.facing());
        assertTrue(!compare.subtract());

        slot.mode = ComparatorMode.SUBTRACT;
        slot.setConnection(Direction.NORTH, ConnectionState.ON);
        Placement subtract = place(slot, EVERYTHING);
        assertTrue(subtract.subtract());
        assertTrue(!subtract.locked(), "vanilla's comparator has no locked property");
    }

    // ------------------------------------------------------------------ torches --

    @Test
    @DisplayName("a torch on the floor is vanilla's floor torch, untouched")
    void floorTorch() {
        // A torch does not power the side it hangs on, and vanilla's floor torch hangs on the block below.
        Placement placement = place(slot(ComponentType.TORCH, Direction.DOWN), EVERYTHING);
        assertEquals(Model.TORCH, placement.model());
        assertEquals(0F, placement.tilt());
        assertTrue(placement.lit());
    }

    @Test
    @DisplayName("a torch on a wall flips its facing, because vanilla's points away from the wall")
    void wallTorch() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            Placement placement = place(slot(ComponentType.TORCH, facing), EVERYTHING);
            assertEquals(Model.WALL_TORCH, placement.model(), "hanging on " + facing);
            assertEquals(facing.getOpposite(), placement.facing(),
                    "vanilla's wall torch points away from the side it hangs on");
            assertEquals(0F, placement.tilt());
        }
    }

    @Test
    @DisplayName("a torch on the ceiling is a floor torch turned upside down")
    void ceilingTorch() {
        Placement placement = place(slot(ComponentType.TORCH, Direction.UP), EVERYTHING);
        assertEquals(Model.TORCH, placement.model(), "vanilla has no ceiling torch to borrow");
        assertEquals(180F, placement.tilt());
    }

    // ---------------------------------------------------------------- switches --

    @Test
    @DisplayName("levers and buttons use the floor, wall and ceiling variants vanilla already has")
    void switchesChooseAttachFace() {
        Placement floor = place(slot(ComponentType.LEVER, Direction.DOWN), EVERYTHING);
        assertEquals(Model.LEVER, floor.model());
        assertEquals(AttachFace.FLOOR, floor.face());

        Placement ceiling = place(slot(ComponentType.LEVER, Direction.UP), EVERYTHING);
        assertEquals(AttachFace.CEILING, ceiling.face());

        Placement wall = place(slot(ComponentType.BUTTON, Direction.NORTH), EVERYTHING);
        assertEquals(Model.BUTTON, wall.model());
        assertEquals(AttachFace.WALL, wall.face());
        assertEquals(Direction.SOUTH, wall.facing(),
                "a wall switch is attached at facing.getOpposite(), like the torch");
        assertEquals(0F, wall.tilt(), "no matrix is needed: vanilla has all six");
    }

    @Test
    @DisplayName("a switch is drawn thrown or released from its own flag, not from its power")
    void switchUsesThrownFlag() {
        Slot slot = slot(ComponentType.LEVER, Direction.DOWN);
        slot.powered = true;
        assertTrue(place(slot, EVERYTHING).lit(), "thrown, so the handle is drawn down");
        slot.powered = false;
        assertTrue(!place(slot, EVERYTHING).lit(),
                "a lever that has been switched off is drawn off even while its power drains");
    }

    // ------------------------------------------------------------------ others --

    @Test
    @DisplayName("a pressure plate has no vanilla model, and says so")
    void pressurePlateHasNoModel() {
        assertNull(InnerComponentVisuals.placementFor(
                        slot(ComponentType.PRESSURE_PLATE, Direction.DOWN), EVERYTHING),
                "nothing is placeable as a plate yet, so the renderer falls back to the box shapes");
    }
}

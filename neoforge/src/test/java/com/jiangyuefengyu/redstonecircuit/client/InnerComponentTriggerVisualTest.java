package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Neighbours;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentVisuals.Model;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentVisuals.Placement;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 触发器 inside a host block: which model it takes, where it points, and which state is drawn.
 *
 * <p>The bug this pins down was "方块内没有贴图": {@code placementFor} had no case for the type and fell through
 * to its {@code default -> null}, so {@code of} handed the renderer nothing and the box fallback had no shape for
 * it either - only the connection bars and the input marker were left, which reads as an empty cell.
 *
 * <p>Before the fix every assertion below failed on the same line: {@code assertNotNull(placement)} in
 * {@link #place}, because there was no {@link Placement} at all. The placement half is a pure value and is
 * therefore checked here; the state half cannot be, since {@code stateOf} resolves {@code RCRegistry.TRIGGER}
 * and a {@code DeferredHolder} is only bound once the game has bootstrapped its registries - so that half is
 * read off the source instead (see {@link #theStateIsTheTriggerBlockItself}).
 */
class InnerComponentTriggerVisualTest {

    private static final Neighbours NOTHING = direction -> false;

    private static Slot slot(Direction facing, int power) {
        Slot slot = new Slot(ComponentType.TRIGGER);
        slot.facing = facing;
        slot.power = power;
        return slot;
    }

    private static Placement place(Slot slot) {
        Placement placement = InnerComponentVisuals.placementFor(slot, NOTHING);
        assertNotNull(placement, "触发器 is placeable inside a host, so it maps onto something");
        return placement;
    }

    @Test
    @DisplayName("a horizontal input face is the model's own FACING, and power picks the texture")
    void horizontalInputFaceTakesTheSlotFacing() {
        Placement dark = place(slot(Direction.NORTH, 0));
        assertEquals(Model.TRIGGER, dark.model(), "触发器 draws our own block, not the box fallback");
        assertEquals(Direction.NORTH, dark.facing(),
                "FACING is the face it reads, which is exactly what Slot#facing means, so it is handed over as is");
        assertEquals(0F, dark.tilt(), "a horizontal input needs no turn");
        assertFalse(dark.lit(), "nothing read is the mod's unlit texture");

        Placement lit = place(slot(Direction.EAST, 15));
        assertEquals(Direction.EAST, lit.facing());
        assertTrue(lit.lit(), "powered is trigger_on: the mod's two textures are the two states");
    }

    @Test
    @DisplayName("a vertical input face is the southern model quarter-turned about X")
    void verticalInputFaceTurnsTheSouthernModel() {
        Placement fromAbove = place(slot(Direction.UP, 15));
        assertEquals(Model.TRIGGER, fromAbove.model());
        assertEquals(Direction.SOUTH, fromAbove.facing(),
                "the block only lies on the floor or hangs from the ceiling, so an input from above has no variant "
                        + "of its own and the southern model is used");
        assertEquals(-90F, fromAbove.tilt(), "the quarter turn is what points the reading face upwards");
        assertTrue(fromAbove.lit());

        Placement fromBelow = place(slot(Direction.DOWN, 0));
        assertEquals(Direction.SOUTH, fromBelow.facing());
        assertEquals(90F, fromBelow.tilt(), "the same model, turned the other way for an input from below");
        assertFalse(fromBelow.lit());
    }

    @Test
    @DisplayName("the state drawn is the trigger's own block, with the four properties it carries")
    void theStateIsTheTriggerBlockItself() throws IOException {
        String visuals = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "InnerComponentVisuals.java");
        String stateOf = slice(visuals, "private static BlockState stateOf(",
                "private static BlockState repeaterState(");

        assertTrue(stateOf.contains("case TRIGGER ->"),
                "a placement that names the model is worthless if no state is assembled for it: the caller then draws "
                        + "nothing at all, which is the report this test answers");
        assertTrue(stateOf.contains("RCRegistry.TRIGGER.get().defaultBlockState()"),
                "the model is this mod's own block - trigger / trigger_on - not a vanilla stand-in");
        assertTrue(stateOf.contains("TriggerBlock.FACE, AttachFace.FLOOR"),
                "the block itself only ever stands on the floor; a vertical input is the renderer's tilt, which the "
                        + "state cannot express");
        assertTrue(stateOf.contains("TriggerBlock.FACING, placement.facing()"),
                "and FACING follows the placement, which for a vertical input is the southern model");
        assertTrue(stateOf.contains("TriggerBlock.POWERED, placement.lit()"),
                "POWERED selects the lit texture, so it has to follow the placement's lit flag");
        assertTrue(stateOf.contains("TriggerBlock.INPUT, false"),
                "INPUT is the block's edge memory, which shows nothing: writing it keeps the drawn state on the "
                        + "author's two ordinary variants");
    }

    @Test
    @DisplayName("the trigger adds no box of its own: the cell holds one geometry, not two")
    void theTriggerAddsNoBoxOfItsOwn() throws IOException {
        String model = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "InnerComponentModel.java");
        String boxes = slice(model, "public static void boxes(", "private static final float LIMIT");

        assertFalse(boxes.contains("TRIGGER"),
                "the block model is the whole picture inside the cell - a box drawn on top of it would be a second "
                        + "geometry in the same 16x16x16; only the connection bars and the input marker belong there");
    }

    // -------------------------------------------------------------- source helpers --

    /** The part of {@code source} between two markers, exclusive of the second: one method, not the whole file. */
    private static String slice(String source, String from, String to) {
        int start = source.indexOf(from);
        assertTrue(start >= 0, "the source no longer contains " + from + ", so this wiring test checked nothing");
        int end = source.indexOf(to, start);
        assertTrue(end > start, "the source no longer contains " + to + " after " + from);
        return source.substring(start, end);
    }

    private static String read(String... parts) throws IOException {
        // The working directory is the neoforge project for the test task, but a build invoked from the repository
        // root is not, so both spellings are tried - and a missing file is a failure rather than a silent skip.
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("not found: " + direct.toAbsolutePath());
    }
}

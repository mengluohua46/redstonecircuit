package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>一条还骑在结构上的记录, 在它原来那一格世界里什么都不答.</b> A carried record's reverse-map entry is keyed by the
 * position it answers at, and that position is its {@code origin} - where the component was taken from - which is a
 * fact about the past. The world store holds nothing there (the claim moved the component out), no live structure
 * projects a host onto it, and the click path cannot reach the record from it either. Answered anyway, the cell reads
 * as a component the world holds: it supplies signal, the notified-neighbour loop counts it as a host, and no click can
 * ever take it away. What is pinned here is that {@code CarrierStore#slotAt} refuses that answer, and - just as
 * important - that the two answers which name a position the component really IS at are untouched: the world store's
 * own component, and a record a live structure <b>draws</b> there (the projection hand-off the world reads a carried
 * host through). Deliberately free of any {@code ServerLevel}: the decision is three values, and this file states them.
 */
class RidingRecordAnswersNothingAtItsWorldPositionTest {

    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000d1ce"));

    /** The structure's own coordinate; for a Sable sub-level a plot coordinate is a level coordinate. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 128, 20524041);

    /** The world position the component was taken from - the number the reverse map answers at. */
    private static final BlockPos ORIGIN = new BlockPos(-57, -60, 18);

    /** A world position nothing at all is about: no record, no component. */
    private static final BlockPos SOMEWHERE_ELSE = new BlockPos(-57, -60, 40);

    /** A stand-in for the world store: a map of positions to components, exactly as the index reaches it. */
    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        private final Map<Long, Slot> slots = new HashMap<>();

        @Override
        public Slot get(BlockPos pos) {
            return slots.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, Slot slot) {
            if (slot == null) {
                slots.remove(pos.asLong());
            } else {
                slots.put(pos.asLong(), slot);
            }
        }
    }

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    /** A torch in the world at {@link #ORIGIN}, then a structure picking it up: the ordinary claim. */
    private void carriedTorch() {
        world.set(ORIGIN, new Slot(ComponentType.TORCH));
        CarriedHostIndex.ClaimOutcome claimed = index.claim(CONTRAPTION, LOCAL, ORIGIN, world);
        assertTrue(claimed.performed(), "the claim moved the component out of the world");
        assertNull(world.get(ORIGIN), "the world store holds nothing at the position it was taken from");
    }

    @Test
    @DisplayName("a riding record answers nothing at the position it was taken from")
    void ridingRecordAnswersNothingAtThePositionItWasTakenFrom() {
        carriedTorch();
        assertTrue(index.isCarried(ORIGIN), "the record is riding, and it answers at its origin");
        // THE OLD ANSWER, STILL THERE at the index: the reverse map is keyed by the origin and says so.
        assertNotNull(index.slotAt(ORIGIN, world),
                "the index itself still answers at the origin; that is what the fix has to gate");

        // THE RULE: nothing draws a host there, so the world-facing question answers "nothing here".
        assertNull(CarrierStore.slotAt(index, world, ORIGIN, false),
                "a riding record must answer nothing at a world position no live structure draws it at");
    }

    @Test
    @DisplayName("the same record still answers where a live structure draws it")
    void theSameRecordStillAnswersWhereAStructureDrawsIt() {
        carriedTorch();
        Slot drawn = CarrierStore.slotAt(index, world, ORIGIN, true);
        assertNotNull(drawn, "the projection hand-off is the one world-side answer a carried host keeps");
        assertEquals(ComponentType.TORCH, drawn.type);
    }

    @Test
    @DisplayName("a record the structure put down answers where it landed")
    void aPutDownRecordAnswersWhereItLanded() {
        carriedTorch();
        index.release(CONTRAPTION, LOCAL, null, world);
        assertFalse(index.isCarried(ORIGIN), "put down is not carried any more");
        Slot landed = CarrierStore.slotAt(index, world, ORIGIN, false);
        assertNotNull(landed, "a component the structure put back is a world host again");
        assertEquals(ComponentType.TORCH, landed.type);
    }

    @Test
    @DisplayName("the world store's own component still answers")
    void theWorldStoresOwnComponentStillAnswers() {
        world.set(SOMEWHERE_ELSE, new Slot(ComponentType.LEVER));
        Slot live = CarrierStore.slotAt(index, world, SOMEWHERE_ELSE, false);
        assertNotNull(live, "a component the level really holds is the ordinary answer");
        assertEquals(ComponentType.LEVER, live.type);
    }

    @Test
    @DisplayName("a riding record that holds nothing answers nothing either")
    void anEmptiedRidingRecordAnswersNothing() {
        carriedTorch();
        assertTrue(index.removeSlot(CONTRAPTION, LOCAL), "the player took the component out of the carried host");
        assertNull(CarrierStore.slotAt(index, world, ORIGIN, false),
                "an empty riding host is not a component at its origin either");
    }

    @Test
    @DisplayName("the level form asks the projection table before it answers from the reverse map")
    void theLevelFormAsksTheProjectionBeforeItAnswersFromTheReverseMap() {
        // The level is the one thing this file cannot hand a test, so the wiring that makes the rule reach
        // production is pinned as text - the same way ControllerWiringTest and ClockWiringTest pin theirs.
        String store = read(List.of("carrier", "CarrierStore.java"));
        assertTrue(store.contains("return slotAt(index, slots, worldPos, isProjectedAt(level, worldPos));"),
                "CarrierStore#slotAt(level, pos) must hand the projection answer to the gated core");
    }

    /** One source file of this module, found from wherever the test is run. */
    private static String read(List<String> tail) {
        List<String> parts = new java.util.ArrayList<>();
        parts.add("src");
        parts.add("main");
        parts.add("java");
        parts.add("com");
        parts.add("jiangyuefengyu");
        parts.add("redstonecircuit");
        parts.addAll(tail);
        for (Path candidate : List.of(Path.of("neoforge").resolve(Path.of("", parts.toArray(String[]::new))),
                Path.of("", parts.toArray(String[]::new)))) {
            if (Files.isRegularFile(candidate)) {
                try {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                } catch (IOException failed) {
                    throw new AssertionError("could not read " + candidate, failed);
                }
            }
        }
        throw new AssertionError("could not find " + tail + " from " + Path.of("").toAbsolutePath());
    }
}

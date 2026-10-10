package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The outward output's rule: a watched cell, a face, and the strength that face answers with while the cell carries a
 * signal.
 *
 * <p>Three shapes are supported - <b>a level</b>, <b>a fixed strength</b>, <b>any non-zero signal</b> - and each one is
 * a decision that a running server would hide rather than show: whether a face goes quiet when its cell does, whether
 * the strongest of two entries wins, and whether a strength of 0 is silence or an error. Those are pinned here,
 * against a reader a test can drive by hand, and the two codecs are round-tripped because the table has to survive a
 * file, a payload and an item.
 */
class StructureOutputTest {

    private static final BlockPos CELL = new BlockPos(1, 0, 2);
    private static final BlockPos OTHER = new BlockPos(0, 0, 0);

    /** A reader that answers for named cells only, the way the block hands in the pocket's own query. */
    private static java.util.function.ToIntFunction<BlockPos> signals(Map<BlockPos, Integer> carried) {
        return cell -> carried.getOrDefault(cell, 0);
    }

    @Test
    @DisplayName("a face answers with its entry's strength while the cell it watches carries a signal")
    void aCarryingCellDrivesItsFace() {
        List<ActivationTable.OutputEntry> table =
                List.of(new ActivationTable.OutputEntry(CELL, Direction.UP, 9));

        int[] carrying = StructureOutput.faceStrengths(table, signals(Map.of(CELL, 4)));
        assertEquals(9, carrying[Direction.UP.ordinal()],
                "the entry's own strength, not the cell's 4: a fixed strength is what the entry names");
        assertEquals(0, carrying[Direction.DOWN.ordinal()], "and no other face says anything");

        int[] quiet = StructureOutput.faceStrengths(table, signals(Map.of()));
        assertEquals(0, quiet[Direction.UP.ordinal()],
                "the moment the cell stops carrying, the face stops answering - it is a level, not a pulse");
    }

    @Test
    @DisplayName("two entries watching one face: the stronger one wins")
    void theStrongerEntryWins() {
        List<ActivationTable.OutputEntry> table = List.of(
                new ActivationTable.OutputEntry(CELL, Direction.EAST, 7),
                new ActivationTable.OutputEntry(OTHER, Direction.EAST, 12));
        int[] both = StructureOutput.faceStrengths(table, signals(Map.of(CELL, 15, OTHER, 15)));
        assertEquals(12, both[Direction.EAST.ordinal()], "the harder of two pushes on one face");

        int[] one = StructureOutput.faceStrengths(table, signals(Map.of(CELL, 15)));
        assertEquals(7, one[Direction.EAST.ordinal()],
                "and when only the weaker one's cell is carrying, the face follows that one");
    }

    @Test
    @DisplayName("faces are independent, and a table that says nothing is silent")
    void facesAreIndependent() {
        List<ActivationTable.OutputEntry> table = List.of(
                new ActivationTable.OutputEntry(CELL, Direction.NORTH, 5),
                new ActivationTable.OutputEntry(CELL, Direction.SOUTH, 11));
        int[] strengths = StructureOutput.faceStrengths(table, signals(Map.of(CELL, 1)));
        assertEquals(5, strengths[Direction.NORTH.ordinal()]);
        assertEquals(11, strengths[Direction.SOUTH.ordinal()], "one cell can watch two faces, each its own strength");

        for (int face : StructureOutput.faceStrengths(List.of(), signals(Map.of(CELL, 15)))) {
            assertEquals(0, face, "an empty table answers nothing at all");
        }
        for (int face : StructureOutput.faceStrengths(null, signals(Map.of(CELL, 15)))) {
            assertEquals(0, face, "and neither does a structure that has no table");
        }
    }

    @Test
    @DisplayName("a strength of 0 is legal in a table and means nothing to emit")
    void aZeroEntryIsSilence() {
        List<ActivationTable.OutputEntry> table =
                List.of(new ActivationTable.OutputEntry(CELL, Direction.UP, 0));
        assertEquals(0, StructureOutput.faceStrengths(table, signals(Map.of(CELL, 15)))[Direction.UP.ordinal()],
                "an entry of 0 is a row a player can see and edit, and it emits nothing");
    }

    @Test
    @DisplayName("a strength outside 0-15 is clamped wherever it was built")
    void strengthsAreClamped() {
        assertEquals(15, new ActivationTable.OutputEntry(CELL, Direction.UP, 200).strength(),
                "a hand-built entry cannot smuggle in a signal above the vanilla range");
        assertEquals(0, new ActivationTable.OutputEntry(CELL, Direction.UP, -5).strength());
        assertThrows(NullPointerException.class, () -> new ActivationTable.OutputEntry(CELL, null, 5),
                "an entry that names no face is a mistake, not a row");
    }

    @Test
    @DisplayName("an entry survives the file codec and the wire")
    void theEntryRoundTrips() {
        ActivationTable.OutputEntry entry = new ActivationTable.OutputEntry(CELL, Direction.WEST, 13);
        var json = ActivationTable.OutputEntry.CODEC.encodeStart(JsonOps.INSTANCE, entry).getOrThrow();
        assertEquals(entry, ActivationTable.OutputEntry.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(),
                "the codec a save file and an item component go through");

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        ActivationTable.OUTPUT_STREAM_CODEC.encode(buffer, entry);
        assertEquals(entry, ActivationTable.OUTPUT_STREAM_CODEC.decode(buffer), "and the codec a packet goes through");

        List<ActivationTable.OutputEntry> table = List.of(entry,
                new ActivationTable.OutputEntry(OTHER, Direction.UP, 3));
        var listJson = ActivationTable.OUTPUT_CODEC.encodeStart(JsonOps.INSTANCE, table).getOrThrow();
        assertEquals(table, ActivationTable.OUTPUT_CODEC.parse(JsonOps.INSTANCE, listJson).getOrThrow(),
                "the whole table, in the order the player built it");
    }
}

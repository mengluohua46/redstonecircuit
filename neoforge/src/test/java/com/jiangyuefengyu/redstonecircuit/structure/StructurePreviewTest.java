package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The preview: the small voxel model of a structure that is allowed to cross the network.
 *
 * <p>This is the one part of a structure a client ever sees, and it has two jobs that pull against each other: it must
 * fit a byte budget, and it must still be recognisable. Both are arithmetic - the coarsening rule, the palette it keeps
 * and the size it happens to encode to - so both are decided here without a running game; a preview that quietly lost
 * the machine it is supposed to show would be a defect no crash and no "the picture exists" assertion could catch.
 */
class StructurePreviewTest {

    private static CompoundTag tag(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    private static final CompoundTag STONE = tag("minecraft:stone");
    private static final CompoundTag DIRT = tag("minecraft:dirt");
    private static final CompoundTag AIR = tag("minecraft:air");

    private static IndexArray cells(int... indices) {
        int paletteSize = 1;
        for (int index : indices) {
            paletteSize = Math.max(paletteSize, index + 1);
        }
        return IndexArray.of(indices, paletteSize);
    }

    @Test
    @DisplayName("a structure that fits keeps one preview cell per block")
    void aSmallStructureIsNotCoarsened() {
        StructurePreview preview = StructurePreview.of(new Vec3i(3, 1, 1), List.of(STONE, AIR),
                cells(0, 1, 0), 65536);

        assertEquals(new Vec3i(3, 1, 1), preview.size());
        assertEquals(1, preview.scale(), "nothing had to be given up to fit the budget");
        assertEquals(3, preview.cellCount());
        assertEquals(2, preview.filledCells(), "the middle cell is air, and air is not 'filled'");
        assertEquals(2, preview.palette().size(), "the preview keeps air and the one block it shows");
        assertTrue(StructurePreview.isAir(preview.palette().get(preview.cells().get(1))),
                "the air cell is drawn as air");
        assertEquals("minecraft:stone", preview.palette().get(preview.cells().get(0)).getString("Name"));
        assertEquals("minecraft:stone", preview.palette().get(preview.cells().get(2)).getString("Name"));
        assertTrue(preview.wireBytes() > 0 && preview.wireBytes() < 200,
                "a three-cell preview is tiny, got " + preview.wireBytes());
    }

    @Test
    @DisplayName("a preview cell coarsens to the most common non-air block in its block of space")
    void aCellKeepsTheBlockThatIsActuallyThere() {
        // Four blocks of structure in one preview cell, one of which is stone: the stone is what a player is
        // looking for, so air must not win on numbers.
        StructurePreview mostlyAir = StructurePreview.build(new Vec3i(4, 1, 1), List.of(STONE, AIR),
                cells(0, 1, 1, 1), 4);

        assertEquals(new Vec3i(1, 1, 1), mostlyAir.size());
        assertEquals(1, mostlyAir.filledCells(), "the stone survives being outnumbered three to one");
        assertEquals("minecraft:stone", mostlyAir.palette().get(mostlyAir.cells().get(0)).getString("Name"));

        // The other way round: air only wins when there is nothing else in the cell at all.
        StructurePreview allAir = StructurePreview.build(new Vec3i(4, 1, 1), List.of(STONE, AIR),
                cells(1, 1, 1, 1), 4);
        assertEquals(0, allAir.filledCells(), "a cell with no blocks in it is empty");
    }

    @Test
    @DisplayName("the most common block wins when a cell holds more than one kind, and a tie goes by name")
    void theMajorityBlockWins() {
        StructurePreview mixed = StructurePreview.build(new Vec3i(3, 1, 1), List.of(STONE, DIRT, AIR),
                cells(0, 1, 1), 3);
        assertEquals("minecraft:dirt", mixed.palette().get(mixed.cells().get(0)).getString("Name"),
                "two dirt against one stone is not a tie");

        // One of each is a tie, and a tie must still be the same answer every time - but "the palette's own order" is
        // not that answer: a live view and an icon never see this structure's palette, they work from block state ids,
        // and the two orders put the same two blocks the other way round. The name is the one key all three callers can
        // produce, so the tie goes to the block named first (see StructurePreviewCells.Rank).
        StructurePreview tied = StructurePreview.build(new Vec3i(2, 1, 1), List.of(STONE, DIRT, AIR),
                cells(0, 1), 2);
        assertEquals(1, tied.filledCells());
        assertEquals("minecraft:dirt", tied.palette().get(tied.cells().get(0)).getString("Name"),
                "one stone and one dirt: dirt is named first, so dirt is what the cell stands for");
    }

    @Test
    @DisplayName("a preview that does not fit the budget is coarsened until it does")
    void overBudgetPreviewsAreCoarsened() {
        int[] indices = new int[32 * 32 * 32];
        StructurePreview preview = StructurePreview.of(new Vec3i(32, 32, 32), List.of(STONE, AIR),
                IndexArray.of(indices, 2), 4096);

        assertEquals(2, preview.scale(), "32 cells of 1 bit each do not fit 4 KB, 16 of them do");
        assertTrue(preview.wireBytes() <= 4096,
                "the whole point of coarsening is fitting the budget, got " + preview.wireBytes());
        assertTrue(preview.cellCount() < indices.length, "and the preview really is smaller than the structure");
    }

    @Test
    @DisplayName("a structure that cannot be described even coarsely keeps the coarsest preview")
    void theCoarsestPreviewIsStillAPreview() {
        List<CompoundTag> palette = new ArrayList<>();
        int[] indices = new int[8 * 8 * 8];
        for (int i = 0; i < indices.length; i++) {
            palette.add(tag("minecraft:block_" + i));
            indices[i] = i;
        }
        StructurePreview preview = StructurePreview.of(new Vec3i(8, 8, 8), palette,
                IndexArray.of(indices, palette.size()), 64);

        assertEquals(StructurePreview.MAX_SCALE, preview.scale(), "it stops at the coarsest scale");
        assertTrue(preview.cellCount() >= 1, "and it is still a preview, not an empty one");
    }

    @Test
    @DisplayName("a preview survives both the save-file codec and the wire")
    void bothCodecsRoundTrip() {
        StructurePreview preview = StructurePreview.of(new Vec3i(3, 2, 1), List.of(STONE, AIR, DIRT),
                cells(0, 2, 1, 0, 2, 1), 65536);

        CompoundTag encoded = (CompoundTag) StructurePreview.CODEC.encodeStart(NbtOps.INSTANCE, preview)
                .result().orElseThrow();
        assertEquals(preview, StructurePreview.CODEC.parse(NbtOps.INSTANCE, encoded).result().orElseThrow());

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        StructurePreview.STREAM_CODEC.encode(buffer, preview);
        assertEquals(preview.wireBytes(), buffer.readableBytes(),
                "the size the budget is about is the size that is actually written");
        assertEquals(preview, StructurePreview.STREAM_CODEC.decode(buffer));
        assertEquals(0, buffer.readableBytes(), "and nothing is left over");
    }

    @Test
    @DisplayName("the render cache key is derived: same picture, same number")
    void theKeyIsThePicture() {
        StructurePreview first = StructurePreview.of(new Vec3i(2, 1, 1), List.of(STONE, AIR),
                cells(0, 1), 65536);
        StructurePreview same = StructurePreview.of(new Vec3i(2, 1, 1), List.of(STONE, AIR),
                cells(0, 1), 65536);
        StructurePreview different = StructurePreview.of(new Vec3i(2, 1, 1), List.of(STONE, AIR),
                cells(1, 1), 65536);

        assertEquals(first.hash(), same.hash(), "two identical structures must share one cache entry");
        assertEquals(first, same);
        assertNotEquals(first.hash(), different.hash(), "and a different picture must not");
    }

    @Test
    @DisplayName("a malformed preview on the wire is rejected rather than believed")
    void malformedPreviewsAreRejected() {
        FriendlyByteBuf emptyPalette = new FriendlyByteBuf(Unpooled.buffer());
        emptyPalette.writeVarInt(1);
        emptyPalette.writeVarInt(1);
        emptyPalette.writeVarInt(1);
        emptyPalette.writeVarInt(1);
        emptyPalette.writeVarInt(0);
        assertThrows(DecoderException.class, () -> StructurePreview.STREAM_CODEC.decode(emptyPalette));

        FriendlyByteBuf nonsenseSize = new FriendlyByteBuf(Unpooled.buffer());
        nonsenseSize.writeVarInt(0);
        nonsenseSize.writeVarInt(1);
        nonsenseSize.writeVarInt(1);
        nonsenseSize.writeVarInt(1);
        nonsenseSize.writeVarInt(1);
        assertThrows(DecoderException.class, () -> StructurePreview.STREAM_CODEC.decode(nonsenseSize));
    }

    @Test
    @DisplayName("a payload whose cells point outside its palette becomes air instead of throwing")
    void acorruptPayloadIsDrawnAsEmpty() {
        // Reachable from a file somebody edited by hand, and from a payload whose palette does not describe
        // every index. "This structure cannot be drawn" is a much better outcome than an exception thrown in
        // the middle of placing a block.
        StructurePreview preview = StructurePreview.of(new Vec3i(2, 1, 1), List.of(STONE),
                cells(0, 5), 65536);

        assertEquals(1, preview.filledCells(), "the cell that does name a block is still drawn");
        assertEquals("minecraft:stone", preview.palette().get(preview.cells().get(0)).getString("Name"));
        assertTrue(StructurePreview.isAir(preview.palette().get(preview.cells().get(1))),
                "the cell that points at nothing is air");

        StructurePreview emptyPalette = StructurePreview.of(new Vec3i(2, 1, 1), List.of(), cells(0, 0), 65536);
        assertEquals(0, emptyPalette.filledCells(), "a payload with no palette at all draws nothing");
    }

    @Test
    @DisplayName("the description says what a picture is, for a log or a bug report")
    void theDescriptionSaysWhatItIs() {
        String described = StructurePreview.of(new Vec3i(3, 1, 1), List.of(STONE, AIR), cells(0, 1, 0), 65536)
                .describe();

        assertTrue(described.contains("3x1x1"), described);
        assertTrue(described.contains("scale 1"), described);
        assertTrue(described.contains("2/3 cell(s) filled"), described);
        assertFalse(described.isEmpty());
    }
}

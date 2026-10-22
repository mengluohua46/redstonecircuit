package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.FriendlyByteBuf;

/**
 * What an item or a placed block carries about a structure, both ways round.
 *
 * <p>The product block injects from the table it was placed with, so the table has to survive <b>both</b> trips
 * - the save file's codec and the packet that syncs an item. A table that survived one and not the other would
 * read as "it works on the server and does nothing after I pick it up".
 */
class StructureProductDataTest {

    private static final BlockPos CELL = new BlockPos(2, 0, 1);

    /**
     * A description with an output table and nothing else: two rows on one face (the list is not a map keyed by
     * face) and a strength of 0, which is legal and means "answer nothing".
     */
    private static StructureProductData withOutput() {
        return new StructureProductData(UUID.randomUUID(), "answering", new Vec3i(4, 3, 5),
                IoMode.ACTIVATION_FACES, 1, Map.of(), List.of(),
                List.of(new ActivationTable.OutputEntry(CELL, Direction.UP, 9),
                        new ActivationTable.OutputEntry(CELL, Direction.EAST, 0)), null);
    }

    @Test
    @DisplayName("the output table survives the save-file codec")
    void outputSurvivesTheCodec() {
        StructureProductData data = withOutput();

        CompoundTag tag = (CompoundTag) StructureProductData.CODEC.encodeStart(NbtOps.INSTANCE, data)
                .result().orElseThrow();
        StructureProductData decoded = StructureProductData.CODEC.parse(NbtOps.INSTANCE, tag)
                .result().orElseThrow();

        assertEquals(data, decoded, "everything, the output table included");
        assertEquals(2, decoded.output().size(), "both rows come back");
        assertEquals(9, decoded.output().get(0).strength(), "with their strengths");
        assertEquals(Direction.UP, decoded.output().get(0).face(), "and the face each of them names");
        assertEquals(0, decoded.output().get(1).strength(), "including the 0 that is legal and silent");
    }

    @Test
    @DisplayName("the output table survives the item's own packet")
    void outputSurvivesTheStreamCodec() {
        StructureProductData data = withOutput();

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        StructureProductData.STREAM_CODEC.encode(buffer, data);
        StructureProductData decoded = StructureProductData.STREAM_CODEC.decode(buffer);

        assertEquals(data, decoded,
                "a structure that lost its output table on the way to an item would stop answering after "
                        + "being picked up");
        assertEquals(0, buffer.readableBytes(), "and the packet holds exactly what was written");
    }

    private static StructureProductData withFaces() {
        return new StructureProductData(UUID.randomUUID(), "driven", new Vec3i(4, 3, 5),
                IoMode.ACTIVATION_FACES, 1,
                Map.of(Direction.EAST, List.of(new ActivationTable.FaceEntry(CELL, 9)),
                        Direction.UP, List.of(new ActivationTable.FaceEntry(BlockPos.ZERO, 15),
                                new ActivationTable.FaceEntry(CELL, 3))));
    }

    @Test
    @DisplayName("the activation table survives the save-file codec")
    void facesSurviveTheCodec() {
        StructureProductData data = withFaces();

        CompoundTag tag = (CompoundTag) StructureProductData.CODEC.encodeStart(NbtOps.INSTANCE, data)
                .result().orElseThrow();
        StructureProductData decoded = StructureProductData.CODEC.parse(NbtOps.INSTANCE, tag)
                .result().orElseThrow();

        assertEquals(data, decoded, "everything, the table included");
        assertEquals(9, decoded.faces().get(Direction.EAST).get(0).strength(), "with the strengths intact");
        assertEquals(2, decoded.faces().get(Direction.UP).size(), "and every entry of a multi-entry face");
    }

    @Test
    @DisplayName("the activation table survives the item's own packet")
    void facesSurviveTheStreamCodec() {
        StructureProductData data = withFaces();

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        StructureProductData.STREAM_CODEC.encode(buffer, data);
        StructureProductData decoded = StructureProductData.STREAM_CODEC.decode(buffer);

        assertEquals(data, decoded, "an item that lost its table would inject nothing after being placed");
        assertEquals(0, buffer.readableBytes(), "and the packet holds exactly what was written");
    }

    @Test
    @DisplayName("a structure with no table round trips as a structure with no table")
    void noTableIsNotAnError() {
        StructureProductData plain = new StructureProductData(UUID.randomUUID(), "plain", new Vec3i(1, 1, 1),
                IoMode.MANUAL, 0);

        assertTrue(plain.faces().isEmpty(), "the five-argument form has no table at all");
        CompoundTag tag = (CompoundTag) StructureProductData.CODEC.encodeStart(NbtOps.INSTANCE, plain)
                .result().orElseThrow();
        assertEquals(plain, StructureProductData.CODEC.parse(NbtOps.INSTANCE, tag).result().orElseThrow());
    }

    @Test
    @DisplayName("a payload's table becomes the product's table, cell for cell")
    void aPayloadsTableBecomesTheProducts() {
        // The palette is hand-made NBT rather than a block state, so a unit test needs no block registry.
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        StructurePayload payload = StructurePayload.of(
                PayloadHeader.fresh(UUID.randomUUID(), "from a capture", new Vec3i(3, 1, 1), BlockPos.ZERO,
                        IoMode.ACTIVATION_FACES, false),
                Map.of(Direction.NORTH, List.of(new ActivationTable.FaceEntry(CELL, 7))),
                List.of(), List.of(stone), IndexArray.of(new int[] {0, 0, 0}, 1),
                List.of(), List.of(), List.of());

        StructureProductData product = StructureProductData.of(payload, 65536, Integer.MAX_VALUE);

        assertEquals(payload.header().uuid(), product.id());
        assertEquals(payload.faces(), product.faces(),
                "what a capture wrote is what the product block will inject from");
        assertThrows(UnsupportedOperationException.class,
                () -> payload.faces().put(Direction.SOUTH, List.of()),
                "and the table a payload exposes is not editable by a caller");
        assertTrue(product.preview() != null && product.preview().filledCells() == 3,
                "and the picture a client draws travels with it too, got " + product.preview());
    }
}

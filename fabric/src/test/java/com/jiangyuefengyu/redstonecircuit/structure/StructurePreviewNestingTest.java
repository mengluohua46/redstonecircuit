package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;

/**
 * A structure inside a structure has to be in the picture.
 *
 * <p>A product block draws nothing by itself ({@code RenderShape.INVISIBLE} - the miniature its block entity
 * renders is its look) and one standing inside a structure has no client-side block entity to draw one, so that
 * cell would be empty. The inner structure's own preview therefore travels inside the outer one: that is
 * {@link NestedPreviews}, one extra palette entry per nested cell.
 *
 * <p>These tests are the arithmetic of that decision, and they need no game.
 */
class StructurePreviewNestingTest {

    private static CompoundTag state(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    /** A one-row preview whose cells are the given block names. */
    private static StructurePreview previewOf(String... names) {
        List<CompoundTag> palette = new ArrayList<>();
        int[] cells = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            palette.add(state(names[i]));
            cells[i] = i;
        }
        return new StructurePreview(new Vec3i(names.length, 1, 1), 1, palette,
                IndexArray.of(cells, palette.size()), 0);
    }

    private static CompoundTag previewTag(StructurePreview preview) {
        return (CompoundTag) StructurePreview.CODEC.encodeStart(NbtOps.INSTANCE, preview).result().orElseThrow();
    }

    /** A payload of {@code cells} cells: cell 0 is stone and every other cell is this mod's product block. */
    private static StructurePayload payloadWithNested(List<StructurePayload.BlockEntityEntry> blockEntities,
                                                      int cells) {
        List<CompoundTag> palette = List.of(state("minecraft:stone"), state("redstonecircuit:structure"));
        int[] indices = new int[cells];
        for (int i = 1; i < cells; i++) {
            indices[i] = 1;
        }
        return StructurePayload.of(
                new PayloadHeader(PayloadHeader.CURRENT_FORMAT, UUID.randomUUID(), "outer", new Vec3i(cells, 1, 1),
                        BlockPos.ZERO, 1, IoMode.ACTIVATION_FACES, false),
                Map.of(), List.of(), List.of(), palette, IndexArray.of(indices, palette.size()),
                List.of(), List.of(), blockEntities);
    }

    /** A block entity tag carrying the given preview, which is what a nested product block holds. */
    private static CompoundTag withPreview(StructurePreview preview) {
        CompoundTag tag = new CompoundTag();
        tag.put("preview", previewTag(preview));
        return tag;
    }

    @Test
    @DisplayName("the cell that holds a structure carries that structure's own picture")
    void aNestedStructuresPictureTravelsInTheOuterPalette() {
        StructurePreview inner = previewOf("minecraft:redstone_block", "minecraft:stone");
        StructurePayload payload = payloadWithNested(List.of(
                new StructurePayload.BlockEntityEntry(new BlockPos(1, 0, 0), withPreview(inner))), 2);

        StructurePreview outer = StructurePreview.ofPayload(payload, 65536);
        assertNotNull(outer, "a payload always has a picture to give");
        int nestedCell = 1;
        int entry = outer.cells().get(nestedCell);
        StructurePreview carried = NestedPreviews.inside(outer.palette().get(entry));
        assertNotNull(carried, "the nested cell must describe the structure inside it: " + outer.describe());
        assertEquals(inner, carried, "and it is that structure's own picture, not a stand-in");
        assertEquals("redstonecircuit:structure",
                NestedPreviews.stateTag(outer.palette().get(entry)).getString("Name"),
                "while the entry still is the block state it describes, because a reader that strips the key is what "
                        + "the client is");
        CompoundTag plain = outer.palette().get(outer.cells().get(0));
        assertNull(NestedPreviews.inside(plain), "and a cell that holds an ordinary block is left alone");
        assertEquals("minecraft:stone", NestedPreviews.stateTag(plain).getString("Name"),
                "which is the block the payload had standing there");
    }

    @Test
    @DisplayName("without a nested structure the payload's own palette and indices are handed straight on")
    void aStructureWithNoNestedOneKeepsItsPalette() {
        StructurePayload payload = payloadWithNested(List.of(), 2);
        NestedPreviews.Expanded expanded = NestedPreviews.expand(payload, 65536);

        assertSame(payload.palette(), expanded.palette(),
                "nothing to describe: the palette is the payload's own, so an ordinary structure pays nothing for this");
        assertSame(payload.indices(), expanded.cells(), "and so are the indices");
        assertNull(NestedPreviews.inside(state("minecraft:stone")),
                "an ordinary entry has no picture hidden in it");
        CompoundTag ordinary = state("minecraft:stone");
        assertSame(ordinary, NestedPreviews.stateTag(ordinary),
                "and stripping the key off an ordinary entry is not a copy, because there is nothing to strip");
    }

    @Test
    @DisplayName("nested pictures are capped in number, and one that does not fit is drawn smaller")
    void nestedPicturesAreCapped() {
        StructurePreview inner = previewOf("minecraft:redstone_block", "minecraft:stone");
        List<StructurePayload.BlockEntityEntry> blockEntities = new ArrayList<>();
        for (int cell = 1; cell < 40; cell++) {
            blockEntities.add(new StructurePayload.BlockEntityEntry(new BlockPos(cell, 0, 0), withPreview(inner)));
        }
        StructurePreview outer = StructurePreview.ofPayload(payloadWithNested(blockEntities, 40), 65536);
        int carried = 0;
        for (CompoundTag entry : outer.palette()) {
            if (NestedPreviews.inside(entry) != null) {
                carried++;
            }
        }
        assertEquals(32, carried, "a structure full of nested ones describes as many as it is allowed to and stops - a "
                + "payload that could grow its own preview without end is one the packet finds out about");

        StructurePreview squeezed = StructurePreview.ofPayload(payloadWithNested(blockEntities, 40), 512);
        assertTrue(squeezed.wireBytes() > 0, "and a squeezed budget still produces a picture: " + squeezed.describe());
        boolean described = false;
        for (CompoundTag entry : squeezed.palette()) {
            described |= NestedPreviews.inside(entry) != null;
        }
        assertTrue(described, "the nested structures are drawn smaller rather than dropped: " + squeezed.describe());
    }
}

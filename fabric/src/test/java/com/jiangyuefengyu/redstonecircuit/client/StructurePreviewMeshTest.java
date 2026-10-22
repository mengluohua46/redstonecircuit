package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.structure.IndexArray;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * The two decisions of the uploaded-miniature cache that are pure: what counts as the same picture, and whether a
 * built mesh is worth a draw call.
 *
 * <p>Both are one line each in the renderer and both are silent when wrong. A key that ignores the live revision
 * answers a running circuit with its previous frame; a key that ignores the picture answers one structure with
 * another's blocks; and a draw of an empty mesh is a state change in the middle of somebody else's pass.
 */
class StructurePreviewMeshTest {

    @Test
    @DisplayName("the key is the picture and the live revision, and nothing else")
    void theKeyIsThePictureAndTheRevision() {
        StructurePreview stone = preview("minecraft:stone");
        StructurePreview dirt = preview("minecraft:dirt");

        assertEquals(StructurePreviewMesh.Key.of(stone, 0, 0), StructurePreviewMesh.Key.of(stone, 0, 0),
                "the same picture at the same revision is one entry: this is what lets the hand, the icon and a placed "
                        + "structure share one emission");
        assertNotEquals(StructurePreviewMesh.Key.of(stone, 0, 0), StructurePreviewMesh.Key.of(stone, 7, 0),
                "a live view that has moved on is another picture, and answering it with the previous one shows the "
                        + "wrong blocks in the right colours");
        assertNotEquals(StructurePreviewMesh.Key.of(stone, 0, 0), StructurePreviewMesh.Key.of(dirt, 0, 0),
                "two structures with different contents are two pictures, whatever revision they are at");
        assertNotEquals(StructurePreviewMesh.Key.of(stone, 0, 0), StructurePreviewMesh.Key.of(stone, 0, 1),
                "and the same picture at two detail levels is two pictures: the icon is half the resolution of the "
                        + "block, so a key that ignored it would draw one in the other's place");
        assertEquals(stone.hash(), StructurePreviewMesh.Key.of(stone, 3, 0).hash(),
                "the first half of the key is the preview's own hash, which every road to a preview derives");
        assertEquals(3, StructurePreviewMesh.Key.of(stone, 3, 0).revision(), "the second half is the revision");
        assertEquals(1, StructurePreviewMesh.Key.of(stone, 3, 1).detail(), "and the last is the detail level");
    }

    @Test
    @DisplayName("a mesh with no vertices is not drawn")
    void anEmptyMeshIsSkipped() {
        assertFalse(StructurePreviewMesh.drawable(0),
                "a render type no cell emitted into - the translucent one, when nothing in the picture is translucent "
                        + "- has a builder and no geometry: drawing it is a state change for nothing");
        assertTrue(StructurePreviewMesh.drawable(4), "and a quad is drawn");
    }

    @Test
    @DisplayName("the cache keeps a bounded number of pictures")
    void theCacheIsBounded() {
        assertEquals(16, StructurePreviewMesh.LIMIT,
                "a structure's picture is native memory: the bound is what keeps a client that has seen many "
                        + "structures from holding every one of them uploaded");
    }

    /** A one-cell preview of one block, which is enough to be a picture with its own hash. */
    private static StructurePreview preview(String block) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", block);
        return new StructurePreview(new Vec3i(1, 1, 1), 1, List.of(tag), IndexArray.of(new int[] {0}, 1), 0);
    }
}

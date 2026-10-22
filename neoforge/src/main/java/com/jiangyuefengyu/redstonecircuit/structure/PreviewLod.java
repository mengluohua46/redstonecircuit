package com.jiangyuefengyu.redstonecircuit.structure;

import net.minecraft.core.Vec3i;

/**
 * A preview's picture at half the resolution, for the places a structure is drawn as an icon.
 *
 * <p>A stack in the hand, in a slot or on the ground is drawn at a few dozen pixels, and what a 32³ structure costs
 * there is the same 3 MB of vertices the placed block costs: a measured 0.7 ms per draw, for a picture whose smallest
 * feature is smaller than a pixel. Half the resolution is a quarter of the cells at the same silhouette - the hand, the
 * slot and the player model's hand all share it - and the rule that decides what a coarsened cell looks like is
 * {@link StructurePreviewCells#representative}, the same one a capture uses, so an icon is a smaller photograph of the
 * same structure and not a different reading of it.
 *
 * <p>Pure: cells are palette indices or block state ids, and "empty" is the caller's own value. Nothing here knows what
 * a block is.
 */
public final class PreviewLod {

    /** The size a preview of {@code size} has at half resolution: each axis rounds up, so one cell stays one cell. */
    public static Vec3i half(Vec3i size) {
        return new Vec3i(Math.max(1, (size.getX() + 1) / 2), Math.max(1, (size.getY() + 1) / 2),
                Math.max(1, (size.getZ() + 1) / 2));
    }

    /**
     * {@code cells} sampled down by two on every axis, into a grid of {@code halfSize}.
     *
     * <p>The group a half cell stands for is 2×2×2 of the original cells, and what it answers is
     * {@link StructurePreviewCells#representative}: the skin of the picture first, then what faces air, then the
     * majority - which is what keeps a decorated wall in front of the layer behind it at the smaller size too.
     *
     * <p><b>A tie is decided by the numbers themselves</b>, because this method is registry-free and knows nothing about
     * blocks, so the caller owes it one numbering: every value handed in must come from the <b>same</b> numbering space,
     * or the same picture answers differently depending on who numbered it. That is not hypothetical - a capture's
     * palette index and a live view's block state id disagree about which of two blocks is the smaller number, and the
     * picture on the ground once disagreed with the one the server sent a moment later by 120 cells for exactly that
     * reason. Both call sites satisfy this today: {@code StructurePreviewGeometry.icon} renumbers its state ids through
     * {@link StructurePreviewCells#numbering} before getting here, and a capture does not come here at all but votes
     * through {@link StructurePreviewCells#representative} with a {@link StructurePreviewCells.Rank}. A caller that
     * hands raw state ids to this method brings that disagreement back.
     *
     * @param cells      the picture, indexed the way {@link StructureLiveView#index} numbers a grid of {@code size}
     * @param emptyValue what "nothing" is in the caller's numbering
     * @param halfSize   {@link #half} of {@code size}
     */
    public static int[] half(int[] cells, int emptyValue, Vec3i size, Vec3i halfSize) {
        int[] reduced = new int[halfSize.getX() * halfSize.getY() * halfSize.getZ()];
        for (int y = 0; y < halfSize.getY(); y++) {
            for (int z = 0; z < halfSize.getZ(); z++) {
                for (int x = 0; x < halfSize.getX(); x++) {
                    int value = StructurePreviewCells.representative(size,
                            (cx, cy, cz) -> cells[StructureLiveView.index(cx, cy, cz, size)],
                            candidate -> candidate == emptyValue, emptyValue, x, y, z, 2);
                    reduced[StructureLiveView.index(x, y, z, halfSize)] = value;
                }
            }
        }
        return reduced;
    }

    private PreviewLod() {
    }
}
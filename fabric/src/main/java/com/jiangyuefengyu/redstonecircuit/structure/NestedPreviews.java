package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

/**
 * A structure inside a structure, as a picture.
 *
 * <p>A product block answers {@code RenderShape.INVISIBLE} - the miniature its block entity renders is its look
 * - so standing inside a structure it stands in the pocket, where no client draws a block entity, and the outer
 * miniature would show a hole. What travels instead is that cell's own block entity data, which already carries
 * the inner structure's {@link StructurePreview}, under {@link #KEY}.
 *
 * <p>{@link #MAX_ENTRIES} and a share of the caller's byte budget cap all of it; what does not fit draws the
 * client's stand-in instead of a hole.
 */
public final class NestedPreviews {

    /** The palette key a nested structure's own preview travels under. A client strips it before reading the state. */
    public static final String KEY = "rc_nested";

    /** How many nested previews one structure may carry. Well past any machine a player builds. */
    private static final int MAX_ENTRIES = 32;

    /** A preview's palette/cells, with one extra entry per nested cell. */
    public record Expanded(List<CompoundTag> palette, IndexArray cells) {
    }

    /**
     * The palette and indices to build a preview from: the payload's own, plus one entry per nested cell
     * carrying that structure's own preview. The nested previews take a share of {@code maxBytes}.
     */
    public static Expanded expand(StructurePayload payload, int maxBytes) {
        Map<Integer, Picture> nested = nestedTags(payload, maxBytes);
        if (nested.isEmpty()) {
            return new Expanded(payload.palette(), payload.indices());
        }
        int[] cells = payload.indices().toIntArray();
        List<CompoundTag> palette = new ArrayList<>(payload.palette());
        int budget = Math.max(512, maxBytes / 4);
        int used = 0;
        for (Map.Entry<Integer, Picture> entry : nested.entrySet()) {
            int cell = entry.getKey();
            if (cell < 0 || cell >= cells.length || used >= budget) {
                continue;
            }
            CompoundTag own = payload.palette().get(cells[cell]).copy();
            own.put(KEY, entry.getValue().tag());
            palette.add(own);
            // Counted in the same units the preview's own budget is: what this picture costs on the wire.
            used += entry.getValue().wireBytes();
            cells[cell] = palette.size() - 1;
        }
        return new Expanded(List.copyOf(palette), IndexArray.of(cells, palette.size()));
    }

    /** The nested structure's preview inside a palette entry, or {@code null} when that entry is an ordinary one. */
    @Nullable
    public static StructurePreview inside(CompoundTag entry) {
        if (!entry.contains(KEY)) {
            return null;
        }
        return StructurePreview.CODEC.parse(NbtOps.INSTANCE, entry.getCompound(KEY)).result().orElse(null);
    }

    /**
     * The same tag without the nested key: an entry carrying a preview is not a block state anybody wrote by hand.
     */
    public static CompoundTag stateTag(CompoundTag entry) {
        if (!entry.contains(KEY)) {
            return entry;
        }
        CompoundTag clean = entry.copy();
        clean.remove(KEY);
        return clean;
    }

    /** One nested structure's picture, and what it costs on the wire - the unit the budget above is in. */
    private record Picture(CompoundTag tag, int wireBytes) {
    }

    /** Every cell whose block entity data carries a preview, by flat cell index, within the budget. */
    private static Map<Integer, Picture> nestedTags(StructurePayload payload, int maxBytes) {
        Map<Integer, Picture> nested = new HashMap<>();
        if (payload.blockEntities().isEmpty()) {
            return nested;
        }
        Vec3i size = payload.header().size();
        int perEntry = Math.max(256, maxBytes / 8);
        for (StructurePayload.BlockEntityEntry entry : payload.blockEntities()) {
            if (nested.size() >= MAX_ENTRIES) {
                break;
            }
            CompoundTag tag = entry.nbt().getCompound("preview");
            if (tag.isEmpty()) {
                continue;
            }
            StructurePreview preview = StructurePreview.CODEC.parse(NbtOps.INSTANCE, tag).result().orElse(null);
            if (preview == null || preview.cellCount() == 0) {
                continue;
            }
            // A nested preview that does not fit its share is drawn smaller rather than dropped.
            if (preview.wireBytes() > perEntry) {
                preview = StructurePreview.of(preview.size(), preview.palette(), preview.cells(), perEntry);
            }
            Tag encoded = StructurePreview.CODEC.encodeStart(NbtOps.INSTANCE, preview).result().orElse(null);
            if (encoded instanceof CompoundTag compound) {
                nested.put(flat(entry.localPos(), size), new Picture(compound, preview.wireBytes()));
            }
        }
        return nested;
    }

    /** The one index formula this mod uses, {@code x + sizeX * (z + sizeZ * y)}. */
    private static int flat(BlockPos pos, Vec3i size) {
        return pos.getX() + size.getX() * (pos.getZ() + size.getZ() * pos.getY());
    }

    private NestedPreviews() {
    }
}

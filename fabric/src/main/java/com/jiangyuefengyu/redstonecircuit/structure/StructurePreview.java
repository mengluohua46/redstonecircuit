package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.Unpooled;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * A small voxel model of a structure, small enough to travel: the complete structure goes to this save's
 * files and never over the network, so a client draws this downsampled preview instead, coarsened until its
 * encoding fits {@code structurePreviewBytes}. A preview cell stands for a {@code scale}³ block and holds the
 * most common non-air block there, so a wire or a torch in an otherwise empty region still shows up - the
 * point is to recognise the machine.
 *
 * <p>{@code hash} is the render cache key, derived in the constructor so the same picture always gets the
 * same number; the preview carries its own palette of block state tags (the registry-free form the payload
 * uses) and no block entities, slot data or context shell.
 */
public record StructurePreview(Vec3i size, int scale, List<CompoundTag> palette, IndexArray cells, int hash) {

    /**
     * The coarsest a preview may get: eight means a 48³ structure is drawn as 6×6×6 blocks, past which a
     * preview stops being a picture of anything. A structure that still does not fit at this scale keeps it
     * rather than growing without end.
     */
    public static final int MAX_SCALE = 8;

    /** The block state names that count as "nothing is here". */
    private static final List<String> AIR_NAMES =
            List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    /** A preview with nothing in it - what a structure whose file is missing is drawn as. */
    public static final StructurePreview EMPTY = new StructurePreview(new Vec3i(1, 1, 1), 1,
            List.of(airTag()), IndexArray.of(new int[] {0}, 1), 0);

    public static final Codec<StructurePreview> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Vec3i.CODEC.fieldOf("size").forGetter(StructurePreview::size),
                    Codec.INT.optionalFieldOf("scale", 1).forGetter(StructurePreview::scale),
                    CompoundTag.CODEC.listOf().fieldOf("palette").forGetter(StructurePreview::palette),
                    IndexArray.CODEC.fieldOf("cells").forGetter(StructurePreview::cells))
            .apply(instance, (size, scale, palette, cells) -> new StructurePreview(size, scale, palette, cells, 0)));

    /**
     * The preview on the wire, which is the form whose size the budget is about. Reading is bounded rather
     * than trusting: an item's components arrive from a client on the creative path, and a palette or a cell
     * count larger than the largest legal structure is a malformed packet, not a structure.
     */
    public static final StreamCodec<FriendlyByteBuf, StructurePreview> STREAM_CODEC = StreamCodec.of(
            (buffer, preview) -> {
                writeSize(buffer, preview.size());
                buffer.writeVarInt(preview.scale());
                buffer.writeVarInt(preview.palette().size());
                for (CompoundTag entry : preview.palette()) {
                    ByteBufCodecs.COMPOUND_TAG.encode(buffer, entry);
                }
                IndexArray.STREAM_CODEC.encode(buffer, preview.cells());
            },
            buffer -> {
                Vec3i size = readSize(buffer);
                int scale = buffer.readVarInt();
                int paletteSize = buffer.readVarInt();
                if (paletteSize < 1 || paletteSize > PayloadLimits.DEFAULT.maxVolume()) {
                    throw new io.netty.handler.codec.DecoderException(
                            "a preview palette cannot hold " + paletteSize + " entries");
                }
                List<CompoundTag> palette = new ArrayList<>(paletteSize);
                for (int i = 0; i < paletteSize; i++) {
                    palette.add(ByteBufCodecs.COMPOUND_TAG.decode(buffer));
                }
                IndexArray cells = IndexArray.STREAM_CODEC.decode(buffer);
                if (cells.count() > PayloadLimits.DEFAULT.maxVolume()) {
                    throw new io.netty.handler.codec.DecoderException(
                            "a preview cannot hold " + cells.count() + " cells");
                }
                return new StructurePreview(size, Math.max(1, scale), palette, cells, 0);
            });

    public StructurePreview {
        palette = List.copyOf(palette);
        // Derived, never stored: the same picture gets the same key whatever road it took here (a file, a
        // packet, or a capture a moment ago).
        hash = Objects.hash(size, scale, palette, cells);
    }

    /** How many cells the preview itself has - not the structure's cell count. */
    public int cellCount() {
        return this.cells.count();
    }

    /** The cell index of a preview coordinate, in the payload's own order. */
    public int index(int x, int y, int z) {
        return x + this.size.getX() * (z + this.size.getZ() * y);
    }

    /** How many bytes this preview takes on the wire - the number the budget is compared against. */
    public int wireBytes() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        STREAM_CODEC.encode(buffer, this);
        return buffer.readableBytes();
    }

    /** How many of the preview's cells are not air - the number a player would call "how much is in it". */
    public int filledCells() {
        int filled = 0;
        for (int i = 0; i < this.cells.count(); i++) {
            int index = this.cells.get(i);
            if (index >= 0 && index < this.palette.size() && !isAir(this.palette.get(index))) {
                filled++;
            }
        }
        return filled;
    }

    /**
     * One line describing this preview, for a command, for the log and for a bug report: one wording in one
     * place, so what a client writes when it draws the thing can be compared with the server's own answer.
     */
    public String describe() {
        return this.size.getX() + "x" + this.size.getY() + "x" + this.size.getZ() + " at scale " + this.scale
                + ", " + filledCells() + "/" + this.cells.count() + " cell(s) filled, palette "
                + this.palette.size() + ", " + wireBytes() + " byte(s) on the wire";
    }

    /** True when a palette entry names air - by name, because this class never needs the block registry. */
    public static boolean isAir(CompoundTag entry) {
        return AIR_NAMES.contains(entry.getString("Name"));
    }

    /**
     * Builds the preview of a structure: the same cells at first, then coarser until it fits {@code maxBytes}.
     *
     * <p>{@code maxBytes} is the budget ({@code structurePreviewBytes}); {@code palette} is the payload's
     * palette, entry for entry, and {@code source} its per-cell palette indices.
     */
    public static StructurePreview of(Vec3i size, List<CompoundTag> palette, IndexArray source, int maxBytes) {
        for (int scale = 1; scale < MAX_SCALE; scale++) {
            StructurePreview candidate = build(size, palette, source, scale);
            if (candidate.wireBytes() <= maxBytes) {
                return candidate;
            }
        }
        return build(size, palette, source, MAX_SCALE);
    }

    /**
     * Builds the preview at one fixed coarseness. Package-private so the tests can ask for a scale directly
     * and pin the coarsening rule instead of only seeing the scale the budget happened to choose.
     */
    static StructurePreview build(Vec3i size, List<CompoundTag> palette, IndexArray source, int scale) {
        int airIndex = -1;
        for (int i = 0; i < palette.size(); i++) {
            if (isAir(palette.get(i))) {
                airIndex = i;
                break;
            }
        }
        int sideX = ceilDiv(size.getX(), scale);
        int sideY = ceilDiv(size.getY(), scale);
        int sideZ = ceilDiv(size.getZ(), scale);

        int[] counts = new int[palette.size()];
        int[] touched = new int[palette.size()];
        int[] chosen = new int[sideX * sideY * sideZ];
        for (int y = 0; y < sideY; y++) {
            for (int z = 0; z < sideZ; z++) {
                for (int x = 0; x < sideX; x++) {
                    chosen[x + sideX * (z + sideZ * y)] =
                            dominant(size, source, airIndex, x, y, z, scale, counts, touched);
                }
            }
        }

        // The preview's own palette: air first (an empty cell is index 0 and IndexArray has no "outside"
        // value), then the entries actually used, in the source's own order so the palette is stable.
        List<CompoundTag> previewPalette = new ArrayList<>();
        previewPalette.add(airIndex >= 0 ? palette.get(airIndex) : airTag());
        int[] remap = new int[palette.size()];
        java.util.Arrays.fill(remap, -1);
        for (int index : chosen) {
            if (index == airIndex || remap[index] >= 0) {
                continue;
            }
            remap[index] = previewPalette.size();
            previewPalette.add(palette.get(index));
        }
        int[] cells = new int[chosen.length];
        for (int i = 0; i < chosen.length; i++) {
            cells[i] = chosen[i] == airIndex ? 0 : remap[chosen[i]];
        }
        return new StructurePreview(new Vec3i(sideX, sideY, sideZ), scale, previewPalette,
                IndexArray.of(cells, previewPalette.size()), 0);
    }

    /**
     * The block one preview cell stands for: the most common non-air one in its block of space, or air when
     * there is none. Ties go to the lower palette index, so the answer never depends on iteration order.
     *
     * <p>An index the palette does not have is treated as air rather than followed. A payload is checked
     * against its palette when it is loaded, but this is also reachable from a file somebody edited by hand,
     * and "this structure cannot be drawn" beats an exception thrown while a player places a block.
     */
    private static int dominant(Vec3i size, IndexArray source, int airIndex, int px, int py, int pz, int scale,
                                int[] counts, int[] touched) {
        int touchedCount = 0;
        int best = -1;
        int bestCount = 0;
        for (int y = py * scale; y < Math.min((py + 1) * scale, size.getY()); y++) {
            for (int z = pz * scale; z < Math.min((pz + 1) * scale, size.getZ()); z++) {
                for (int x = px * scale; x < Math.min((px + 1) * scale, size.getX()); x++) {
                    int index = source.get(x + size.getX() * (z + size.getZ() * y));
                    if (index < 0 || index >= counts.length || index == airIndex) {
                        continue;
                    }
                    if (counts[index] == 0) {
                        touched[touchedCount++] = index;
                    }
                    counts[index]++;
                    if (counts[index] > bestCount || (counts[index] == bestCount && index < best)) {
                        best = index;
                        bestCount = counts[index];
                    }
                }
            }
        }
        for (int i = 0; i < touchedCount; i++) {
            counts[touched[i]] = 0;
        }
        return best < 0 ? airIndex : best;
    }

    private static CompoundTag airTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:air");
        return tag;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static void writeSize(FriendlyByteBuf buffer, Vec3i size) {
        buffer.writeVarInt(size.getX());
        buffer.writeVarInt(size.getY());
        buffer.writeVarInt(size.getZ());
    }

    private static Vec3i readSize(FriendlyByteBuf buffer) {
        int x = buffer.readVarInt();
        int y = buffer.readVarInt();
        int z = buffer.readVarInt();
        if (x < 1 || y < 1 || z < 1 || (long) x * y * z > PayloadLimits.DEFAULT.maxVolume()) {
            throw new io.netty.handler.codec.DecoderException(
                    "a preview of " + x + "x" + y + "x" + z + " is not a structure");
        }
        return new Vec3i(x, y, z);
    }

    /**
     * The preview of a payload, or null for a caller that has no payload (the missing-placeholder case).
     *
     * <p>The palette is asked whether the payload holds any <b>nested</b> structure - a product block standing
     * inside this one - and if it does, that structure's own preview travels inside the entry for its cell
     * (see {@link NestedPreviews}); the cell would otherwise be drawn as an invisible block.
     */
    @Nullable
    public static StructurePreview ofPayload(@Nullable StructurePayload payload, int maxBytes) {
        if (payload == null) {
            return null;
        }
        NestedPreviews.Expanded expanded = NestedPreviews.expand(payload, maxBytes);
        return of(payload.header().size(), expanded.palette(), expanded.cells(), maxBytes);
    }
}

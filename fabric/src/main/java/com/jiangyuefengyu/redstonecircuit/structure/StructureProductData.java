package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * What an item or a placed block knows about a structure: its id, and just enough to describe it.
 *
 * <p>A structure's data lives in the save's files, so the thing a player carries is an <b>id plus a description</b> -
 * never the structure. That is what makes a product item cheap to sync, cheap to stack and impossible to forge into a
 * different structure, and it is why an item that finds no file for its id shows the missing placeholder instead.
 *
 * <p>{@code nestingDepth}, {@code faces} and {@code output} travel with it so that capture can answer "how deep would
 * this be" and a placed product can know which cells each face pushes into and what strength each answers with, all
 * without opening the save file; {@code output} is the only table that points outwards. All three are small - a handful
 * of cells, not a structure.
 *
 * <p>{@code preview} is a downsampled voxel model ({@link StructurePreview}) within {@code structurePreviewBytes}, which
 * a client draws on the placed block or in the hand without ever being sent the structure. It is nullable: a description
 * that cannot draw itself is the missing-placeholder case, and a client has to be able to tell that from a structure
 * that happens to be one block of air.
 */
public record StructureProductData(UUID id, String name, Vec3i size, IoMode ioMode, int nestingDepth,
                                   Map<Direction, List<ActivationTable.FaceEntry>> faces,
                                   List<ActivationTable.ManualEntry> manual,
                                   List<ActivationTable.OutputEntry> output,
                                   @Nullable StructurePreview preview) {

    public static final Codec<StructureProductData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(StructureProductData::id),
                    Codec.STRING.optionalFieldOf("name", PayloadHeader.DEFAULT_NAME)
                            .forGetter(StructureProductData::name),
                    Vec3i.CODEC.fieldOf("size").forGetter(StructureProductData::size),
                    IoMode.CODEC.optionalFieldOf("io_mode", IoMode.ACTIVATION_FACES)
                            .forGetter(StructureProductData::ioMode),
                    Codec.INT.optionalFieldOf("nesting_depth", 0)
                            .forGetter(StructureProductData::nestingDepth),
                    ActivationTable.TABLE_CODEC.optionalFieldOf("faces", Map.of())
                            .forGetter(StructureProductData::faces),
                    ActivationTable.ManualEntry.CODEC.listOf().optionalFieldOf("manual", List.of())
                            .forGetter(StructureProductData::manual),
                    ActivationTable.OUTPUT_CODEC.optionalFieldOf("output", List.of())
                            .forGetter(StructureProductData::output),
                    StructurePreview.CODEC.optionalFieldOf("preview")
                            .forGetter(data -> Optional.ofNullable(data.preview())))
            .apply(instance, (id, name, size, ioMode, nestingDepth, faces, manual, output, preview) ->
                    new StructureProductData(id, name, size, ioMode, nestingDepth, faces, manual, output,
                            preview.orElse(null))));

    /** Three var-ints: an item carries a size, not a position, so there is no packed form to reuse. */
    public static final StreamCodec<FriendlyByteBuf, Vec3i> SIZE_STREAM_CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeVarInt(value.getX());
                buffer.writeVarInt(value.getY());
                buffer.writeVarInt(value.getZ());
            },
            buffer -> new Vec3i(buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt()));

    public static final StreamCodec<FriendlyByteBuf, StructureProductData> STREAM_CODEC =
            StreamCodec.of(StructureProductData::write, StructureProductData::read);

    private static void write(FriendlyByteBuf buffer, StructureProductData data) {
        UUIDUtil.STREAM_CODEC.encode(buffer, data.id());
        buffer.writeUtf(data.name(), PayloadHeader.MAX_NAME_LENGTH);
        SIZE_STREAM_CODEC.encode(buffer, data.size());
        IoMode.STREAM_CODEC.encode(buffer, data.ioMode());
        buffer.writeVarInt(data.nestingDepth());
        ActivationTable.TABLE_STREAM_CODEC.encode(buffer, data.faces());
        buffer.writeVarInt(data.manual().size());
        for (ActivationTable.ManualEntry entry : data.manual()) {
            ActivationTable.MANUAL_STREAM_CODEC.encode(buffer, entry);
        }
        buffer.writeVarInt(data.output().size());
        for (ActivationTable.OutputEntry entry : data.output()) {
            ActivationTable.OUTPUT_STREAM_CODEC.encode(buffer, entry);
        }
        // A boolean rather than an empty preview: "this description cannot draw itself" (the
        // missing-placeholder case) is a state of its own, and a client has to be able to tell it from a
        // structure that happens to be one block of air.
        buffer.writeBoolean(data.preview() != null);
        if (data.preview() != null) {
            StructurePreview.STREAM_CODEC.encode(buffer, data.preview());
        }
    }

    private static StructureProductData read(FriendlyByteBuf buffer) {
        UUID id = UUIDUtil.STREAM_CODEC.decode(buffer);
        // Bounded at the decoder as well as at the writer: a name a client sent is not a length anybody typed.
        String name = buffer.readUtf(PayloadHeader.MAX_NAME_LENGTH);
        Vec3i size = SIZE_STREAM_CODEC.decode(buffer);
        IoMode ioMode = IoMode.STREAM_CODEC.decode(buffer);
        int nestingDepth = buffer.readVarInt();
        Map<Direction, List<ActivationTable.FaceEntry>> faces =
                ActivationTable.TABLE_STREAM_CODEC.decode(buffer);
        int manualCount = buffer.readVarInt();
        if (manualCount < 0 || manualCount > PayloadLimits.DEFAULT.maxManualEntries()) {
            throw new io.netty.handler.codec.DecoderException(
                    "a manual panel cannot hold " + manualCount + " entries");
        }
        List<ActivationTable.ManualEntry> manual = new ArrayList<>(manualCount);
        for (int i = 0; i < manualCount; i++) {
            manual.add(ActivationTable.MANUAL_STREAM_CODEC.decode(buffer));
        }
        // Bounded like the face table is: an output entry names a cell inside the selection, so a list longer
        // than the largest selection is a malformed packet rather than a big table.
        int outputCount = buffer.readVarInt();
        if (outputCount < 0 || outputCount > PayloadLimits.DEFAULT.maxVolume()) {
            throw new io.netty.handler.codec.DecoderException(
                    "an output table cannot hold " + outputCount + " entries");
        }
        List<ActivationTable.OutputEntry> output = new ArrayList<>(outputCount);
        for (int i = 0; i < outputCount; i++) {
            output.add(ActivationTable.OUTPUT_STREAM_CODEC.decode(buffer));
        }
        StructurePreview preview = buffer.readBoolean() ? StructurePreview.STREAM_CODEC.decode(buffer) : null;
        return new StructureProductData(id, name, size, ioMode, nestingDepth, faces, manual, output, preview);
    }

    public StructureProductData {
        name = name == null || name.isEmpty() ? PayloadHeader.DEFAULT_NAME : name;
        faces = faces == null ? Map.of() : Map.copyOf(faces);
        manual = manual == null ? List.of() : List.copyOf(manual);
        output = output == null ? List.of() : List.copyOf(output);
    }

    /** A structure with no activation table, no manual panel and no preview - what a bare tool has. */
    public StructureProductData(UUID id, String name, Vec3i size, IoMode ioMode, int nestingDepth) {
        this(id, name, size, ioMode, nestingDepth, Map.of(), List.of(), List.of(), null);
    }

    /** A structure with an activation table but nothing else. */
    public StructureProductData(UUID id, String name, Vec3i size, IoMode ioMode, int nestingDepth,
                                Map<Direction, List<ActivationTable.FaceEntry>> faces) {
        this(id, name, size, ioMode, nestingDepth, faces, List.of(), List.of(), null);
    }

    /**
     * The tables and a preview, with no output table: a structure whose cells answer no strength. Kept so that every
     * place building a description - tests, tools, the block entity - reads "this structure has no output table" as an
     * empty list rather than as an absent argument.
     */
    public StructureProductData(UUID id, String name, Vec3i size, IoMode ioMode, int nestingDepth,
                                Map<Direction, List<ActivationTable.FaceEntry>> faces,
                                List<ActivationTable.ManualEntry> manual,
                                @Nullable StructurePreview preview) {
        this(id, name, size, ioMode, nestingDepth, faces, manual, List.of(), preview);
    }

    /** The same description read off a captured payload: activation tables, manual panel and preview. */
    public static StructureProductData of(StructurePayload payload, int previewBytes) {
        return new StructureProductData(payload.header().uuid(), payload.header().name(),
                payload.header().size(), payload.header().ioMode(), payload.header().nestingDepth(),
                payload.faces(), payload.manual(), payload.output(),
                StructurePreview.ofPayload(payload, previewBytes));
    }

    /** The same description read off a payload header alone, for a caller that has nothing else. */
    public static StructureProductData of(PayloadHeader header) {
        return new StructureProductData(header.uuid(), header.name(), header.size(), header.ioMode(),
                header.nestingDepth());
    }
}

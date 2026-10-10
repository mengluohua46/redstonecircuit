package com.jiangyuefengyu.redstonecircuit.structure;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One captured structure: the complete data, and the only thing that ever goes on disk. Loader-free on
 * purpose - the fabric build mirrors this file verbatim - and the palette holds raw block-state tags rather
 * than resolved states, because the unit tests run without bootstrapping the registries.
 * <p>{@link #contextShell}, {@link #slotData} and {@link #settingData} carry what is not in a block's state
 * - the ring of world around the selection, this mod's components inside blocks, a clock's settings - so a
 * placed structure answers from what it captured; {@link #output} is the one table pointing out of it.
 */
public record StructurePayload(
        PayloadHeader header,
        Map<Direction, List<ActivationTable.FaceEntry>> faces,
        List<ActivationTable.ManualEntry> manual,
        List<ActivationTable.OutputEntry> output,
        List<CompoundTag> palette,
        IndexArray indices,
        List<ShellEntry> contextShell,
        List<SlotEntry> slotData,
        List<SettingEntry> settingData,
        List<BlockEntityEntry> blockEntities,
        int payloadHash) {

    public static final Codec<StructurePayload> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    PayloadHeader.CODEC.fieldOf("header").forGetter(StructurePayload::header),
                    Codec.unboundedMap(Direction.CODEC, ActivationTable.FaceEntry.CODEC.listOf())
                            .optionalFieldOf("faces", Map.of()).forGetter(StructurePayload::faces),
                    ActivationTable.ManualEntry.CODEC.listOf()
                            .optionalFieldOf("manual", List.of()).forGetter(StructurePayload::manual),
                    ActivationTable.OUTPUT_CODEC.optionalFieldOf("output", List.of())
                            .forGetter(StructurePayload::output),
                    CompoundTag.CODEC.listOf().fieldOf("palette").forGetter(StructurePayload::palette),
                    IndexArray.CODEC.fieldOf("indices").forGetter(StructurePayload::indices),
                    ShellEntry.CODEC.listOf().optionalFieldOf("context_shell", List.of())
                            .forGetter(StructurePayload::contextShell),
                    SlotEntry.CODEC.listOf().optionalFieldOf("slot_data", List.of())
                            .forGetter(StructurePayload::slotData),
                    SettingEntry.CODEC.listOf().optionalFieldOf("setting_data", List.of())
                            .forGetter(StructurePayload::settingData),
                    BlockEntityEntry.CODEC.listOf()
                            .optionalFieldOf("block_entities", List.of())
                            .forGetter(StructurePayload::blockEntities),
                    Codec.INT.optionalFieldOf("payload_hash", 0).forGetter(StructurePayload::payloadHash))
            .apply(instance, StructurePayload::new));

    public StructurePayload {
        Map<Direction, List<ActivationTable.FaceEntry>> copiedFaces = new HashMap<>();
        faces.forEach((direction, entries) -> copiedFaces.put(direction, List.copyOf(entries)));
        faces = Map.copyOf(copiedFaces);
        manual = List.copyOf(manual);
        output = List.copyOf(output);
        List<CompoundTag> copiedPalette = new ArrayList<>(palette.size());
        palette.forEach(tag -> copiedPalette.add(tag.copy()));
        palette = List.copyOf(copiedPalette);
        contextShell = List.copyOf(contextShell);
        slotData = List.copyOf(slotData);
        settingData = List.copyOf(settingData);
        blockEntities = List.copyOf(blockEntities);
    }

    /** The same payload without a clock's settings or an output table. Not a convenience: rebuilding a
     * payload - renaming one, say - has nothing to say about either, and a change of this shape cannot
     * be compiled in halves. */
    public StructurePayload(PayloadHeader header,
                            Map<Direction, List<ActivationTable.FaceEntry>> faces,
                            List<ActivationTable.ManualEntry> manual,
                            List<CompoundTag> palette,
                            IndexArray indices,
                            List<ShellEntry> contextShell,
                            List<SlotEntry> slotData,
                            List<BlockEntityEntry> blockEntities,
                            int payloadHash) {
        this(header, faces, manual, List.of(), palette, indices, contextShell, slotData, List.of(),
                blockEntities, payloadHash);
    }

    /** The same, carrying a clock's settings - what {@code StructureWorldStore#importFrom} needs. */
    public StructurePayload(PayloadHeader header,
                            Map<Direction, List<ActivationTable.FaceEntry>> faces,
                            List<ActivationTable.ManualEntry> manual,
                            List<CompoundTag> palette,
                            IndexArray indices,
                            List<ShellEntry> contextShell,
                            List<SlotEntry> slotData,
                            List<SettingEntry> settingData,
                            List<BlockEntityEntry> blockEntities,
                            int payloadHash) {
        this(header, faces, manual, List.of(), palette, indices, contextShell, slotData, settingData,
                blockEntities, payloadHash);
    }

    /** One block of the world just outside the selection. The position may sit one block outside it. */
    public record ShellEntry(BlockPos relativePos, int paletteIndex) {

        public static final Codec<ShellEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(ShellEntry::relativePos),
                        Codec.INT.fieldOf("palette").forGetter(ShellEntry::paletteIndex))
                .apply(instance, ShellEntry::new));
    }

    /** This mod's own component inside a block, at a coordinate inside the selection. */
    public record SlotEntry(BlockPos localPos, CompoundTag node) {

        public static final Codec<SlotEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(SlotEntry::localPos),
                        CompoundTag.CODEC.fieldOf("node").forGetter(SlotEntry::node))
                .apply(instance, SlotEntry::new));

        public SlotEntry {
            node = node.copy();
        }
    }

    /** A device's settings at a coordinate inside the selection: data this mod keeps <em>beside</em> a
     * block rather than in it, so it travels in the payload - there is nothing to re-derive a player's
     * period from. */
    public record SettingEntry(BlockPos localPos, CompoundTag setting) {

        public static final Codec<SettingEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(SettingEntry::localPos),
                        CompoundTag.CODEC.fieldOf("setting").forGetter(SettingEntry::setting))
                .apply(instance, SettingEntry::new));

        public SettingEntry {
            setting = setting.copy();
        }
    }

    /** One block entity's saved data, at its coordinate inside the selection. */
    public record BlockEntityEntry(BlockPos localPos, CompoundTag nbt) {

        public static final Codec<BlockEntityEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(BlockEntityEntry::localPos),
                        CompoundTag.CODEC.fieldOf("nbt").forGetter(BlockEntityEntry::nbt))
                .apply(instance, BlockEntityEntry::new));

        public BlockEntityEntry {
            nbt = nbt.copy();
        }
    }

    /** A payload whose hash has not been worked out yet. */
    public static StructurePayload of(PayloadHeader header,
                                      Map<Direction, List<ActivationTable.FaceEntry>> faces,
                                      List<ActivationTable.ManualEntry> manual,
                                      List<ActivationTable.OutputEntry> output,
                                      List<CompoundTag> palette,
                                      IndexArray indices,
                                      List<ShellEntry> contextShell,
                                      List<SlotEntry> slotData,
                                      List<SettingEntry> settingData,
                                      List<BlockEntityEntry> blockEntities) {
        StructurePayload withoutHash = new StructurePayload(header, faces, manual, output, palette, indices,
                contextShell, slotData, settingData, blockEntities, 0);
        return withoutHash.withHash(withoutHash.computeHash());
    }

    /** The same, without a clock's settings - what a rebuilder that has none to pass calls. */
    public static StructurePayload of(PayloadHeader header,
                                      Map<Direction, List<ActivationTable.FaceEntry>> faces,
                                      List<ActivationTable.ManualEntry> manual,
                                      List<ActivationTable.OutputEntry> output,
                                      List<CompoundTag> palette,
                                      IndexArray indices,
                                      List<ShellEntry> contextShell,
                                      List<SlotEntry> slotData,
                                      List<BlockEntityEntry> blockEntities) {
        return of(header, faces, manual, output, palette, indices, contextShell, slotData, List.of(),
                blockEntities);
    }

    /** The same without an output table - see the auxiliary constructor above for why both spellings exist. */
    public static StructurePayload of(PayloadHeader header,
                                      Map<Direction, List<ActivationTable.FaceEntry>> faces,
                                      List<ActivationTable.ManualEntry> manual,
                                      List<CompoundTag> palette,
                                      IndexArray indices,
                                      List<ShellEntry> contextShell,
                                      List<SlotEntry> slotData,
                                      List<BlockEntityEntry> blockEntities) {
        return of(header, faces, manual, List.of(), palette, indices, contextShell, slotData, List.of(),
                blockEntities);
    }

    public StructurePayload withHash(int hash) {
        return new StructurePayload(this.header, this.faces, this.manual, this.output, this.palette,
                this.indices, this.contextShell, this.slotData, this.settingData, this.blockEntities, hash);
    }

    /** The render cache key: same structure, same number. Computed from a <b>canonical</b> text form
     * rather than from NBT, because NBT compounds are backed by hash maps and a tag read back can
     * iterate in another order - which would give one structure two meshes and a cache that never hits.
     * A cache key, not an integrity check: {@link #sha256Hex} verifies the bytes on disk. */
    public int computeHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalText().getBytes(StandardCharsets.UTF_8));
            return (digest[0] & 0xFF) << 24 | (digest[1] & 0xFF) << 16
                    | (digest[2] & 0xFF) << 8 | (digest[3] & 0xFF);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }

    private String canonicalText() {
        StringBuilder out = new StringBuilder(1024);
        PayloadHeader head = this.header;
        out.append(head.formatVersion()).append('|').append(head.uuid()).append('|').append(head.name())
                .append('|').append(head.size().getX()).append(',').append(head.size().getY()).append(',')
                .append(head.size().getZ()).append('|').append(head.relativeOrigin().asLong())
                .append('|').append(head.nestingDepth()).append('|').append(head.ioMode().getSerializedName())
                .append('|').append(head.byCut());
        // The six faces in declaration order, never in map order.
        for (Direction direction : Direction.values()) {
            for (ActivationTable.FaceEntry entry : this.faces.getOrDefault(direction, List.of())) {
                out.append("|f").append(direction.getSerializedName()).append(entry.localPos().asLong())
                        .append(':').append(entry.strength());
            }
        }
        for (ActivationTable.ManualEntry entry : this.manual) {
            out.append("|m").append(entry.key()).append(entry.localPos().asLong()).append(':')
                    .append(entry.strength()).append(':').append(entry.label());
        }
        // The output table stays a list: its order is the order the player added the entries in.
        for (ActivationTable.OutputEntry entry : this.output) {
            out.append("|o").append(entry.localPos().asLong())
                    .append(entry.face().getSerializedName()).append(':').append(entry.strength());
        }
        for (CompoundTag tag : this.palette) {
            appendTag(out, tag);
        }
        out.append("|i").append(this.indices.bitsPerIndex()).append(':').append(this.indices.count())
                .append(':');
        for (int word : this.indices.words()) {
            out.append(word).append(',');
        }
        // The position-keyed lists in position order, so the order they were collected in cannot reach the key.
        sortedByPosition(this.contextShell, StructurePayload.ShellEntry::relativePos)
                .forEach(entry -> out.append("|s").append(entry.relativePos().asLong()).append(':')
                        .append(entry.paletteIndex()));
        sortedByPosition(this.slotData, StructurePayload.SlotEntry::localPos)
                .forEach(entry -> {
                    out.append("|d").append(entry.localPos().asLong());
                    appendTag(out, entry.node());
                });
        sortedByPosition(this.settingData, StructurePayload.SettingEntry::localPos)
                .forEach(entry -> {
                    out.append("|t").append(entry.localPos().asLong());
                    appendTag(out, entry.setting());
                });
        sortedByPosition(this.blockEntities, StructurePayload.BlockEntityEntry::localPos)
                .forEach(entry -> {
                    out.append("|b").append(entry.localPos().asLong());
                    appendTag(out, entry.nbt());
                });
        return out.toString();
    }

    private static <T> List<T> sortedByPosition(List<T> list, java.util.function.Function<T, BlockPos> key) {
        List<T> copy = new ArrayList<>(list);
        copy.sort(java.util.Comparator.comparingLong(value -> key.apply(value).asLong()));
        return copy;
    }

    /** A tag with its keys in order, recursing into compounds and lists. */
    private static void appendTag(StringBuilder out, CompoundTag tag) {
        List<String> keys = new ArrayList<>(tag.getAllKeys());
        java.util.Collections.sort(keys);
        out.append('{');
        for (String key : keys) {
            out.append(key).append('=');
            Tag value = tag.get(key);
            if (value instanceof CompoundTag compound) {
                appendTag(out, compound);
            } else if (value instanceof net.minecraft.nbt.ListTag list) {
                out.append('[');
                for (Tag element : list) {
                    if (element instanceof CompoundTag compound) {
                        appendTag(out, compound);
                    } else {
                        out.append(element);
                    }
                    out.append(',');
                }
                out.append(']');
            } else {
                out.append(value);
            }
            out.append(';');
        }
        out.append('}');
    }

    /** Throws when this payload may not be used; capture and loading both call it. Entry coordinates
     * are checked against the selection, because an entry pointing outside it is what a hand-edited
     * file gets wrong and the injector would otherwise silently drop. */
    public void validate(PayloadLimits limits) throws PayloadFormatException {
        PayloadHeader head = this.header;
        if (head.formatVersion() != PayloadHeader.CURRENT_FORMAT) {
            throw new PayloadFormatException("the structure file is format " + head.formatVersion()
                    + ", this build reads format " + PayloadHeader.CURRENT_FORMAT);
        }
        Optional<String> refused = limits.refuse(head.size(), this.palette.size(), this.indices.count(),
                this.manual.size());
        if (refused.isPresent()) {
            throw new PayloadFormatException(refused.get());
        }
        if (!this.indices.refersOnlyTo(this.palette.size())) {
            throw new PayloadFormatException("a block index points outside a palette of "
                    + this.palette.size() + " entries");
        }
        for (Map.Entry<Direction, List<ActivationTable.FaceEntry>> face : this.faces.entrySet()) {
            for (ActivationTable.FaceEntry entry : face.getValue()) {
                if (!head.contains(entry.localPos())) {
                    throw new PayloadFormatException("activation entry " + entry.localPos()
                            + " on face " + face.getKey() + " is outside " + head.size().toShortString());
                }
            }
        }
        for (ActivationTable.ManualEntry entry : this.manual) {
            if (!head.contains(entry.localPos())) {
                throw new PayloadFormatException("manual entry " + entry.localPos()
                        + " is outside " + head.size().toShortString());
            }
        }
        for (ActivationTable.OutputEntry entry : this.output) {
            if (!head.contains(entry.localPos())) {
                throw new PayloadFormatException("output entry " + entry.localPos()
                        + " is outside " + head.size().toShortString());
            }
        }
        for (BlockEntityEntry entry : this.blockEntities) {
            if (!head.contains(entry.localPos())) {
                throw new PayloadFormatException("block entity data at " + entry.localPos()
                        + " is outside " + head.size().toShortString());
            }
        }
        for (SlotEntry entry : this.slotData) {
            if (!head.contains(entry.localPos())) {
                throw new PayloadFormatException("slot data at " + entry.localPos()
                        + " is outside " + head.size().toShortString());
            }
        }
        for (SettingEntry entry : this.settingData) {
            if (!head.contains(entry.localPos())) {
                throw new PayloadFormatException("clock settings at " + entry.localPos()
                        + " is outside " + head.size().toShortString());
            }
        }
        for (ShellEntry entry : this.contextShell) {
            if (!insideShell(head, entry.relativePos())) {
                throw new PayloadFormatException("shell data at " + entry.relativePos()
                        + " is not in the ring around " + head.size().toShortString());
            }
            if (entry.paletteIndex() < 0 || entry.paletteIndex() >= this.palette.size()) {
                throw new PayloadFormatException("shell data at " + entry.relativePos()
                        + " points outside a palette of " + this.palette.size() + " entries");
            }
        }
    }

    /** The ring one block outside the selection, in selection-relative coordinates. */
    public static boolean insideShell(PayloadHeader header, BlockPos relative) {
        int limitX = header.size().getX();
        int limitY = header.size().getY();
        int limitZ = header.size().getZ();
        if (relative.getX() < -1 || relative.getX() > limitX
                || relative.getY() < -1 || relative.getY() > limitY
                || relative.getZ() < -1 || relative.getZ() > limitZ) {
            return false;
        }
        return relative.getX() == -1 || relative.getX() == limitX
                || relative.getY() == -1 || relative.getY() == limitY
                || relative.getZ() == -1 || relative.getZ() == limitZ;
    }

    // ------------------------------------------------------------------ palette --

    /** The palette entry for a block state, as it is stored: no registry needed to write it. */
    public static CompoundTag paletteEntry(BlockState state) {
        return NbtUtils.writeBlockState(state);
    }

    /** The block state a palette entry names. Needs a running game - the block registry is the point. */
    public BlockState resolvePalette(HolderGetter<Block> blocks, int index) {
        return NbtUtils.readBlockState(blocks, this.palette.get(index));
    }

    // --------------------------------------------------------------------- bytes --

    /** The payload as it goes on disk: gzipped NBT, exactly what {@link #fromBytes} reads back. */
    public byte[] toBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(this.toTag(), out);
        return out.toByteArray();
    }

    /** Reads a payload back and checks it against {@code limits}: bytes that do not gunzip, or a tag
     * that is not one of ours, arrive as {@link PayloadFormatException} - never as a half-read
     * structure. */
    public static StructurePayload fromBytes(byte[] bytes, PayloadLimits limits)
            throws PayloadFormatException {
        CompoundTag tag;
        try (InputStream in = new ByteArrayInputStream(bytes)) {
            tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException corrupt) {
            throw new PayloadFormatException(
                    "these bytes are not a readable structure file: " + corrupt, corrupt);
        }
        return fromTag(tag, limits);
    }

    /** Reads a payload from a structure file. A file that is not there throws
     * {@code NoSuchFileException} out of {@link Files#newInputStream} unwrapped: a missing file and a
     * broken one have to stay tellable apart. */
    public static StructurePayload readFrom(Path file, PayloadLimits limits) throws IOException {
        // Opened OUTSIDE the try below: a try-with-resources initialiser sits inside its own try block,
        // so opening the file there would let the catch report a missing file as a corrupt one.
        InputStream opened = Files.newInputStream(file);
        CompoundTag tag;
        try (InputStream in = opened) {
            tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (IOException | RuntimeException corrupt) {
            // RuntimeException as well: the decompressor reports some damage that way, and "this file
            // is broken" must not arrive at a caller as whatever the reader happened to throw.
            throw new PayloadFormatException(file + " is not a readable structure file: " + corrupt, corrupt);
        }
        return fromTag(tag, limits);
    }

    /** Writes the payload to a structure file, replacing whatever was there. */
    public void writeTo(Path file) throws IOException {
        NbtIo.writeCompressed(this.toTag(), file);
    }

    private static StructurePayload fromTag(CompoundTag tag, PayloadLimits limits)
            throws PayloadFormatException {
        StructurePayload payload = CODEC.parse(NbtOps.INSTANCE, tag).result()
                .orElseThrow(() -> new PayloadFormatException("the structure file is not in this mod's format"));
        payload.validate(limits);
        return payload;
    }

    private CompoundTag toTag() {
        Tag tag = CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .orElseThrow(() -> new IllegalStateException("this payload cannot be written"));
        if (!(tag instanceof CompoundTag compound)) {
            throw new IllegalStateException("a payload must write as a compound tag, got " + tag);
        }
        return compound;
    }

    /** The file's checksum, for comparing a copy against the file it came from. */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder text = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                text.append(Character.forDigit((value >> 4) & 0xF, 16));
                text.append(Character.forDigit(value & 0xF, 16));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required of every JVM", impossible);
        }
    }
}

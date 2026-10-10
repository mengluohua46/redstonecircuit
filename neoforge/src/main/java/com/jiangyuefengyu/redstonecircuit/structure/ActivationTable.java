package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.handler.codec.DecoderException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The data behind "a face received a signal, so inject this strength here".
 *
 * <p>一条记录是 {@code {localPos, strength}}: 坐标在<em>选区内部</em>（不是世界坐标），强度必填、0-15，
 * 由 {@link Codec#intRange} 把关，手改的文件也塞不进 200。没有记录的面在 payload 的 map 里缺席，含义是
 * "这个面不做任何事"，不是错误。
 */
public final class ActivationTable {

    /** The vanilla redstone range, and the only strengths an entry may carry. */
    public static final int MIN_STRENGTH = 0;
    public static final int MAX_STRENGTH = 15;

    /** One cell to inject into, and how hard. */
    public record FaceEntry(BlockPos localPos, int strength) {

        public static final Codec<FaceEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(FaceEntry::localPos),
                        Codec.intRange(MIN_STRENGTH, MAX_STRENGTH)
                                .fieldOf("strength").forGetter(FaceEntry::strength))
                .apply(instance, FaceEntry::new));

        /** A strength is 0-15 whatever road it took here: the codec checks a file and the stream codec a packet,
         * so this is the one place every road passes through, and a signal above 15 breaks every piece of
         * redstone arithmetic that reads it. */
        public FaceEntry {
            strength = Math.clamp(strength, MIN_STRENGTH, MAX_STRENGTH);
        }
    }

    /** One panel entry of the manual mode. {@code key} is the <b>key mapping name</b> ("key.keyboard.g", or one of
     * ours) and not a GLFW keycode - a keycode means different keys on different layouts, and a saved structure has
     * to keep working for whoever loads it. {@code label} is optional display text; empty means "the key's name". */
    public record ManualEntry(String key, BlockPos localPos, int strength, String label) {

        public static final Codec<ManualEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        Codec.STRING.fieldOf("key").forGetter(ManualEntry::key),
                        BlockPos.CODEC.fieldOf("pos").forGetter(ManualEntry::localPos),
                        Codec.intRange(MIN_STRENGTH, MAX_STRENGTH)
                                .fieldOf("strength").forGetter(ManualEntry::strength),
                        Codec.STRING.optionalFieldOf("label", "").forGetter(ManualEntry::label))
                .apply(instance, ManualEntry::new));

        public ManualEntry {
            key = key == null ? "" : key;
            label = label == null ? "" : label;
            strength = Math.clamp(strength, MIN_STRENGTH, MAX_STRENGTH);
        }
    }

    /** Long enough for {@code key.keyboard.whatever}; far shorter than a description's worth of text. */
    public static final int MAX_KEY_LENGTH = 64;

    /** Long enough for the sentence a player types as a label. */
    public static final int MAX_LABEL_LENGTH = 64;

    /** <b>One entry of the output table: a cell inside the selection, a face of the block, and the strength that
     * face answers with while that cell carries a signal.</b> The face table goes inwards; this is the same table
     * turned around - the coordinate is the thing <em>watched</em>, not the one driven. Three answers fix the shape:
     * a <b>level</b> (0 when the cell's signal stops), a strength <b>fixed</b> by the entry, and <b>any non-zero</b>
     * signal counting. A list rather than a map, because several cells may watch one face, in the order added. */
    public record OutputEntry(BlockPos localPos, Direction face, int strength) {

        public static final Codec<OutputEntry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(OutputEntry::localPos),
                        Direction.CODEC.fieldOf("face").forGetter(OutputEntry::face),
                        Codec.intRange(MIN_STRENGTH, MAX_STRENGTH).fieldOf("strength").forGetter(OutputEntry::strength))
                .apply(instance, OutputEntry::new));

        public OutputEntry {
            // A strength is 0-15 whatever road it took here, exactly as a face entry's is - and an entry that names
            // no face is not a table row, it is a mistake.
            strength = Math.clamp(strength, MIN_STRENGTH, MAX_STRENGTH);
            java.util.Objects.requireNonNull(face, "an output entry has to name a face");
        }
    }

    /** The output table as a file, a payload or an item component: a plain list, in the order it was built. */
    public static final Codec<List<OutputEntry>> OUTPUT_CODEC = OutputEntry.CODEC.listOf();

    /** One output entry on the wire. */
    public static final StreamCodec<FriendlyByteBuf, OutputEntry> OUTPUT_STREAM_CODEC = StreamCodec.of(
            (buffer, entry) -> {
                BlockPos.STREAM_CODEC.encode(buffer, entry.localPos());
                buffer.writeEnum(entry.face());
                buffer.writeVarInt(entry.strength());
            },
            buffer -> {
                BlockPos localPos = BlockPos.STREAM_CODEC.decode(buffer);
                Direction face = buffer.readEnum(Direction.class);
                int strength = buffer.readVarInt();
                if (strength < MIN_STRENGTH || strength > MAX_STRENGTH) {
                    throw new DecoderException("an output entry cannot emit " + strength);
                }
                return new OutputEntry(localPos, face, strength);
            });

    /** One panel entry on the wire, for the item/block description a client gets its keys from. */
    public static final StreamCodec<FriendlyByteBuf, ManualEntry> MANUAL_STREAM_CODEC = StreamCodec.of(
            (buffer, entry) -> {
                ByteBufCodecs.STRING_UTF8.encode(buffer, entry.key());
                BlockPos.STREAM_CODEC.encode(buffer, entry.localPos());
                buffer.writeVarInt(entry.strength());
                ByteBufCodecs.STRING_UTF8.encode(buffer, entry.label());
            },
            buffer -> {
                String key = ByteBufCodecs.STRING_UTF8.decode(buffer);
                BlockPos localPos = BlockPos.STREAM_CODEC.decode(buffer);
                int strength = buffer.readVarInt();
                if (strength < MIN_STRENGTH || strength > MAX_STRENGTH) {
                    throw new DecoderException("a manual entry cannot push " + strength);
                }
                String label = ByteBufCodecs.STRING_UTF8.decode(buffer);
                // The key is what a client polls; a name longer than any key name is a malformed packet
                // rather than a key that happens to be long.
                if (key.length() > MAX_KEY_LENGTH || label.length() > MAX_LABEL_LENGTH) {
                    throw new DecoderException("a manual entry's key or label is too long");
                }
                return new ManualEntry(key, localPos, strength, label);
            });

    /** Whether a table is small enough for the configured limit. A question rather than an exception, so the capture
     * path (which has to tell the player why) and the loading path (which has to reject a file) each say it their way. */
    public static Optional<String> refuse(List<?> entries, int limit, String what) {
        return entries.size() <= limit
                ? Optional.empty()
                : Optional.of(what + " has " + entries.size() + " entries, the limit is " + limit);
    }

    /** Shorthand for the faces a table has nothing to say about. */
    public static List<FaceEntry> entries(Map<Direction, List<FaceEntry>> table, Direction face) {
        return table == null ? List.of() : table.getOrDefault(face, List.of());
    }

    /** <b>The whole six-face table</b> - the one type both the payload and the item carry, so a table cannot mean
     * one thing in a file and another in a player's hand. */
    public static final Codec<Map<Direction, List<FaceEntry>>> TABLE_CODEC =
            Codec.unboundedMap(Direction.CODEC, FaceEntry.CODEC.listOf());

    /** One entry on the wire: a cell and its strength. */
    public static final StreamCodec<FriendlyByteBuf, FaceEntry> FACE_STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, FaceEntry::localPos,
            ByteBufCodecs.VAR_INT, FaceEntry::strength,
            FaceEntry::new);

    /** The table on the wire, for the item a player carries. Written face by face in {@link Direction#values()} order
     * with the empty faces omitted, because the payload's hash depends on that order. Reading is bounded rather than
     * trusting: an item's components arrive from a client, and no legal payload has more cells than {@link PayloadLimits}. */
    public static final StreamCodec<FriendlyByteBuf, Map<Direction, List<FaceEntry>>> TABLE_STREAM_CODEC =
            StreamCodec.of(ActivationTable::writeTable, ActivationTable::readTable);

    private static void writeTable(FriendlyByteBuf buffer, Map<Direction, List<FaceEntry>> table) {
        Map<Direction, List<FaceEntry>> safe = table == null ? Map.of() : table;
        int present = 0;
        for (Direction face : Direction.values()) {
            if (!entries(safe, face).isEmpty()) {
                present++;
            }
        }
        buffer.writeVarInt(present);
        for (Direction face : Direction.values()) {
            List<FaceEntry> entries = entries(safe, face);
            if (entries.isEmpty()) {
                continue;
            }
            buffer.writeEnum(face);
            buffer.writeVarInt(entries.size());
            for (FaceEntry entry : entries) {
                FACE_STREAM_CODEC.encode(buffer, entry);
            }
        }
    }

    private static Map<Direction, List<FaceEntry>> readTable(FriendlyByteBuf buffer) {
        int present = buffer.readVarInt();
        if (present < 0 || present > Direction.values().length) {
            throw new DecoderException("a face table has at most 6 faces, got " + present);
        }
        Map<Direction, List<FaceEntry>> table = new EnumMap<>(Direction.class);
        for (int face = 0; face < present; face++) {
            Direction direction = buffer.readEnum(Direction.class);
            int count = buffer.readVarInt();
            if (count < 0 || count > PayloadLimits.DEFAULT.maxVolume()) {
                throw new DecoderException("a face table cannot hold " + count + " entries");
            }
            List<FaceEntry> entries = new ArrayList<>(count);
            for (int entry = 0; entry < count; entry++) {
                entries.add(FACE_STREAM_CODEC.decode(buffer));
            }
            table.put(direction, List.copyOf(entries));
        }
        return Map.copyOf(table);
    }

    private ActivationTable() {
    }
}

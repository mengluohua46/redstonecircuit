package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.IntStream;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * The payload's per-cell palette indices, bit-packed into 32-bit words.
 *
 * <p>This is the one genuinely large array in a payload - a 48³ structure is 110,592 cells - so the width
 * follows the palette (2 states = 1 bit/cell, 17 states = 5 bits/cell) and the packed size is what the
 * preview byte budget is measured against.
 *
 * <p>{@code words} is {@code ceil(count * bitsPerIndex / 32)} long, values are packed little-endian (cell
 * {@code i} starts at bit {@code i * bitsPerIndex}), and the unused high bits of the last word are always
 * zero, so two equal arrays encode to equal bytes.
 *
 * <p>Arrays do not compare by value, so {@link #equals} and {@link #hashCode} are spelled out rather than
 * taken from the record.
 */
public record IndexArray(int bitsPerIndex, int count, int[] words) {

    public static final Codec<IndexArray> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("bits").forGetter(IndexArray::bitsPerIndex),
                    Codec.INT.fieldOf("count").forGetter(IndexArray::count),
                    Codec.INT_STREAM.fieldOf("words").forGetter(array -> IntStream.of(array.words())))
            .apply(instance, (bits, count, words) -> new IndexArray(bits, count, words.toArray())));

    /**
     * The same array on the wire, for the one thing that has to fit a network budget: the preview a client
     * draws a structure with. Var-ints rather than the NBT form's int stream, because the number that has to
     * stay below {@code structurePreviewBytes} is the number of bytes actually sent.
     */
    public static final StreamCodec<FriendlyByteBuf, IndexArray> STREAM_CODEC = StreamCodec.of(
            (buffer, array) -> {
                buffer.writeVarInt(array.bitsPerIndex);
                buffer.writeVarInt(array.count);
                buffer.writeVarInt(array.words.length);
                for (int word : array.words) {
                    buffer.writeInt(word);
                }
            },
            buffer -> {
                int bits = buffer.readVarInt();
                int count = buffer.readVarInt();
                int words = buffer.readVarInt();
                if (words < 0 || words > wordCount(count, bits) + 1) {
                    throw new io.netty.handler.codec.DecoderException(
                            "an index array of " + count + " cell(s) at " + bits + " bit(s) cannot have "
                                    + words + " word(s)");
                }
                int[] packed = new int[words];
                for (int i = 0; i < words; i++) {
                    packed[i] = buffer.readInt();
                }
                return new IndexArray(bits, count, packed);
            });

    public IndexArray {
        if (bitsPerIndex < 1 || bitsPerIndex > 32) {
            throw new IllegalArgumentException("bits per index out of range: " + bitsPerIndex);
        }
        if (count < 0) {
            throw new IllegalArgumentException("negative cell count: " + count);
        }
        int expected = wordCount(count, bitsPerIndex);
        if (words.length != expected) {
            throw new IllegalArgumentException(
                    "expected " + expected + " word(s) for " + count + " cells at " + bitsPerIndex
                            + " bit(s), got " + words.length);
        }
        words = words.clone();
    }

    /**
     * Packs {@code values}, whose elements must all be below {@code paletteSize} - the width follows it, so
     * an empty palette (which no payload may have) is rejected rather than producing a zero-width array.
     */
    public static IndexArray of(int[] values, int paletteSize) {
        if (paletteSize < 1) {
            throw new IllegalArgumentException("palette size must be positive, got " + paletteSize);
        }
        int bits = bitsFor(paletteSize);
        int[] words = new int[wordCount(values.length, bits)];
        for (int i = 0; i < values.length; i++) {
            int value = values[i];
            if (value < 0 || value >= paletteSize) {
                throw new IllegalArgumentException(
                        "index " + value + " at cell " + i + " is outside a palette of " + paletteSize);
            }
            put(words, i, bits, value);
        }
        return new IndexArray(bits, values.length, words);
    }

    /** An empty array - what a payload that has not been filled in yet carries. */
    public static IndexArray empty() {
        return new IndexArray(1, 0, new int[0]);
    }

    /** How many bits one cell needs for a palette of this size. */
    public static int bitsFor(int paletteSize) {
        return paletteSize <= 1 ? 1 : 32 - Integer.numberOfLeadingZeros(paletteSize - 1);
    }

    private static int wordCount(int count, int bits) {
        return (int) (((long) count * bits + 31L) / 32L);
    }

    private static void put(int[] words, int index, int bits, int value) {
        long bit = (long) index * bits;
        int word = (int) (bit >>> 5);
        int shift = (int) (bit & 31L);
        words[word] |= value << shift;
        if (shift + bits > 32) {
            words[word + 1] |= value >>> (32 - shift);
        }
    }

    /** The index of one cell, or {@code -1} when there is no such cell. */
    public int get(int index) {
        if (index < 0 || index >= this.count) {
            return -1;
        }
        long bit = (long) index * this.bitsPerIndex;
        int word = (int) (bit >>> 5);
        int shift = (int) (bit & 31L);
        long value = (this.words[word] & 0xFFFFFFFFL) >>> shift;
        if (shift + this.bitsPerIndex > 32 && word + 1 < this.words.length) {
            value |= (this.words[word + 1] & 0xFFFFFFFFL) << (32 - shift);
        }
        return (int) (value & ((1L << this.bitsPerIndex) - 1L));
    }

    /** The unpacked indices, for the code that actually walks a structure. */
    public int[] toIntArray() {
        int[] out = new int[this.count];
        for (int i = 0; i < this.count; i++) {
            out[i] = get(i);
        }
        return out;
    }

    /** The packed size in bytes - what the design's size table is about. */
    public int byteSize() {
        return this.words.length * Integer.BYTES;
    }

    /** True when no cell refers to {@code paletteSize} or beyond. */
    public boolean refersOnlyTo(int paletteSize) {
        for (int i = 0; i < this.count; i++) {
            if (this.get(i) >= paletteSize) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IndexArray array
                && this.bitsPerIndex == array.bitsPerIndex
                && this.count == array.count
                && Arrays.equals(this.words, array.words);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.bitsPerIndex, this.count, Arrays.hashCode(this.words));
    }

    @Override
    public String toString() {
        return "IndexArray[" + this.count + " cell(s), " + this.bitsPerIndex + " bit(s), "
                + this.byteSize() + " byte(s)]";
    }
}

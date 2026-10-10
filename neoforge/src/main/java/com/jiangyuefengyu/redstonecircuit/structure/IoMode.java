package com.jiangyuefengyu.redstonecircuit.structure;

import com.mojang.serialization.Codec;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * How a placed structure is told what to do from outside - 激活面模式 / 手动使用模式.
 *
 * <p>Both modes inject the same thing (a strength at a local coordinate, see {@link ActivationTable}); they differ in
 * who presses the button: the six faces read the world's redstone, or a player presses a key. The mode is written into
 * the payload at capture time, which is why it is part of the header rather than of the block entity.
 */
public enum IoMode implements StringRepresentable {

    /** A: each of the six faces has a table; a face with no table does nothing at all. */
    ACTIVATION_FACES("activation_faces"),

    /** B: the same tables, plus a panel of player-bound keys. */
    MANUAL("manual");

    public static final Codec<IoMode> CODEC = StringRepresentable.fromEnum(IoMode::values);

    /**
     * One byte on the wire, by ordinal: the codec only ever runs between a server and its own clients on the same
     * build, so there is no old client to keep compatible and a name would cost bytes per item sync for no gain.
     */
    public static final StreamCodec<FriendlyByteBuf, IoMode> STREAM_CODEC = StreamCodec.of(
            (buffer, mode) -> buffer.writeByte(mode.ordinal()),
            buffer -> {
                int ordinal = buffer.readUnsignedByte();
                IoMode[] values = values();
                return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ACTIVATION_FACES;
            });

    private final String name;

    IoMode(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }
}

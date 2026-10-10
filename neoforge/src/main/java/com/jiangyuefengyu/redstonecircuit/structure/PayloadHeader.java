package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;

/**
 * Everything about a payload that is small and needed <em>before</em> the structure itself is fetched:
 * which format it is, which file it lives in, what it is called, how big it is, and how it is driven.
 *
 * <p>This is the half the item carries. The design's storage decision (single save: the complete
 * structure goes to {@code <world>/redstonecircuit/structures/<uuid>.nbt}, the item carries only an id
 * plus a small preview) is why {@link #uuid} is here at all: an item that finds no file for its uuid
 * shows the missing placeholder instead of a structure, and is not allowed to release.
 *
 * <p>The design writes the alignment field as a single {@code originOffset} int. A single int cannot
 * name an offset in three axes, and P1 needs a real one, so it is stored as {@link #relativeOrigin} -
 * the design's intent, in the only form that can actually be used. No meaning has been added to it:
 * it is "where the selection sat relative to the controller".
 */
public record PayloadHeader(
        int formatVersion,
        UUID uuid,
        String name,
        Vec3i size,
        BlockPos relativeOrigin,
        int nestingDepth,
        IoMode ioMode,
        boolean byCut) {

    /** Bumped whenever the file format changes in a way old readers cannot handle. */
    public static final int CURRENT_FORMAT = 1;

    /** What a payload is called when its player has not renamed it. */
    public static final String DEFAULT_NAME = "structure";

    /**
     * The longest a structure name may be, in characters.
     *
     * <p>Bounded because a name is <b>typed by a player</b> (the screen has a text box) and then travels: into the
     * payload's header, into the item's data component, into every block entity update tag and into the file. The
     * same ceiling a manual key or label has, so the three "typed strings" in this feature have one rule rather
     * than three.
     */
    public static final int MAX_NAME_LENGTH = 64;

    public static final Codec<PayloadHeader> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                    Codec.INT.fieldOf("format_version").forGetter(PayloadHeader::formatVersion),
                    UUIDUtil.CODEC.fieldOf("uuid").forGetter(PayloadHeader::uuid),
                    Codec.STRING.optionalFieldOf("name", DEFAULT_NAME).forGetter(PayloadHeader::name),
                    Vec3i.CODEC.fieldOf("size").forGetter(PayloadHeader::size),
                    BlockPos.CODEC.optionalFieldOf("relative_origin", BlockPos.ZERO)
                            .forGetter(PayloadHeader::relativeOrigin),
                    Codec.INT.optionalFieldOf("nesting_depth", 0).forGetter(PayloadHeader::nestingDepth),
                    IoMode.CODEC.optionalFieldOf("io_mode", IoMode.ACTIVATION_FACES)
                            .forGetter(PayloadHeader::ioMode),
                    Codec.BOOL.optionalFieldOf("by_cut", false).forGetter(PayloadHeader::byCut))
            .apply(instance, PayloadHeader::new));

    public PayloadHeader {
        // Truncated rather than refused: a name is a label, and a save written by a build that allowed longer
        // ones (or a hand-edited file) must still load as "the name, cut to what this build can carry".
        name = name == null || name.isEmpty() ? DEFAULT_NAME : name;
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH);
        }
    }

    /** A header for a structure captured right now. */
    public static PayloadHeader fresh(UUID uuid, String name, Vec3i size, BlockPos relativeOrigin,
                                      IoMode ioMode, boolean byCut) {
        return new PayloadHeader(CURRENT_FORMAT, uuid, name, size, relativeOrigin, 0, ioMode, byCut);
    }

    /** How many cells the structure has - the number the indices must describe. */
    public long volume() {
        return (long) this.size.getX() * this.size.getY() * this.size.getZ();
    }

    /** True when this cell is inside the selection. */
    public boolean contains(BlockPos localPos) {
        return localPos.getX() >= 0 && localPos.getX() < this.size.getX()
                && localPos.getY() >= 0 && localPos.getY() < this.size.getY()
                && localPos.getZ() >= 0 && localPos.getZ() < this.size.getZ();
    }
}

package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

import org.jetbrains.annotations.Nullable;

/**
 * The <b>stable</b> identity of one moving structure that is carrying host blocks: the first half of the key the carried
 * data is stored under, the second half being the local coordinate inside that structure (see {@link CarriedHost}).
 *
 * <p>The carrier has to survive things "the contraption object" does not - the four to five repeated
 * {@code stopMoving} callbacks Create makes for one motion, a save taken in mid-flight, the gap between
 * {@code startMoving} and {@code stopMoving} - so the identity is a plain serialisable value: {@code kind} (a Create
 * contraption or a Sable sub-level, see {@link CarrierKind}) and {@code uuid} (the contraption <b>entity</b>'s UUID, or
 * the sub-level's), both assigned once when the structure is created and alive until it is taken apart.
 *
 * <p>The contraption's <em>anchor</em> is deliberately not the id: it is a position, not an identity, and two
 * contraptions assembled at the same anchor would collide, while the carried store's entries must never be confused.
 */
public record CarrierId(CarrierKind kind, UUID uuid) {

    /** The carrier a Create contraption entity stands for, from {@code AbstractContraptionEntity#getUUID()}. */
    public static CarrierId contraption(UUID entityUuid) {
        return new CarrierId(CarrierKind.CONTRAPTION, entityUuid);
    }

    /** The carrier a Sable sub-level stands for, from {@code SubLevel#getUniqueId()}. */
    public static CarrierId subLevel(UUID subLevelUuid) {
        return new CarrierId(CarrierKind.SUB_LEVEL, subLevelUuid);
    }

    /** True when this names a Create contraption rather than a Sable sub-level. */
    public boolean isContraption() {
        return kind == CarrierKind.CONTRAPTION;
    }

    /** True when this names a Sable sub-level rather than a Create contraption. */
    public boolean isSubLevel() {
        return kind == CarrierKind.SUB_LEVEL;
    }

    /**
     * The short form used in every log line and in {@code /rc physics}.
     *
     * <p>Eight hex digits of the UUID, because that is long enough that two carriers in one world
     * cannot be told apart wrongly by eye, and short enough that a log line stays a log line.
     */
    public String shortId() {
        String hex = uuid.toString().replace("-", "");
        return (isContraption() ? "c:" : "s:") + hex.substring(0, 8);
    }

    @Override
    public String toString() {
        return shortId() + " (" + (isContraption() ? "create contraption" : "sable sub-level")
                + " uuid=" + uuid + ")";
    }

    // ------------------------------------------------------------------ NBT --

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", kind.name());
        tag.putUUID("uuid", uuid);
        return tag;
    }

    /** Reads one back, or {@code null} when the tag is not a carrier id at all. */
    @Nullable
    public static CarrierId load(@Nullable CompoundTag tag) {
        if (tag == null || !tag.hasUUID("uuid")) {
            return null;
        }
        return new CarrierId(CarrierKind.byName(tag.getString("kind")), tag.getUUID("uuid"));
    }
}

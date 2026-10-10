package com.jiangyuefengyu.redstonecircuit.carrier;

import org.jetbrains.annotations.Nullable;

/**
 * What kind of moving structure a {@link CarrierId} names.
 * <p>{@link #CONTRAPTION}: a Create contraption, identified by its contraption <em>entity</em>'s UUID, which Create
 * keeps for the whole life of the moving structure and never reuses; {@link #SUB_LEVEL}: a Sable sub-level, identified
 * by the sub-level's own UUID. The kind is part of the identity on purpose: the two must never be confused.
 */
public enum CarrierKind {

    /** A Create contraption, identified by its contraption entity's UUID. */
    CONTRAPTION,

    /** A Sable sub-level, identified by the sub-level's UUID. */
    SUB_LEVEL;

    /** By name, for NBT; never {@code null} - an unknown name reads back as {@link #CONTRAPTION}. */
    public static CarrierKind byName(@Nullable String name) {
        if (name != null) {
            for (CarrierKind kind : values()) {
                if (kind.name().equalsIgnoreCase(name)) {
                    return kind;
                }
            }
        }
        return CONTRAPTION;
    }
}

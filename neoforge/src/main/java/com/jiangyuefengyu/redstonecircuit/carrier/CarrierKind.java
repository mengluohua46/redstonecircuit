package com.jiangyuefengyu.redstonecircuit.carrier;

import org.jetbrains.annotations.Nullable;

/**
 * What kind of moving structure a {@link CarrierId} names.
 *
 * <p>Two things can carry a host block away, and they are different enough that the identity has to
 * say which one it is:
 *
 * <ul>
 *   <li>{@link #CONTRAPTION} - a <b>Create</b> contraption. The id is the contraption
 *       <em>entity</em>'s UUID, which Create keeps for the whole life of the moving structure
 *       (assembly to disassembly), and which is not reused afterwards.</li>
 *   <li>{@link #SUB_LEVEL} - a <b>Sable</b> sub-level. The id is the sub-level's own UUID, the same
 *       one {@code SubLevelContainer.getSubLevel(UUID)} takes. This is the case that also covers a
 *       structure whose sub-level was already on disk when the world loaded.</li>
 * </ul>
 *
 * <p>The kind is part of the identity on purpose: a Create contraption flying on a Sable sub-level
 * produces both a contraption entity and a sub-level, and the two must never be confused for one
 * another in the bookkeeping.
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

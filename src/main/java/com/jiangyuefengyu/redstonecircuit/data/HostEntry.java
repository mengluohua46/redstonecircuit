package com.jiangyuefengyu.redstonecircuit.data;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * One host block: where it is, and what is inside it.
 *
 * <p>Exists so the wire format (a list of hosts) does not have to name a client class, and so the
 * position and its contents cannot be sent as parallel lists that drift out of step.
 *
 * <p>A {@code null} slot is meaningful in an update - "this block no longer holds anything" - which is
 * what lets one list carry placements, changes and removals together. A snapshot never contains one.
 */
public record HostEntry(BlockPos pos, @Nullable Slot slot) {

    /** The same position, with nothing inside it. */
    public static HostEntry removed(BlockPos pos) {
        return new HostEntry(pos, null);
    }
}

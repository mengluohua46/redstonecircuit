package com.jiangyuefengyu.redstonecircuit.client;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * The client's copy of "which block my wrench picked", for highlighting only.
 *
 * <p>The authoritative selection lives on the server ({@code WrenchSelection}); this is a mirror of
 * it, filled in by a payload, and it decides nothing. It exists because the renderer has to draw a mark
 * around a block that no client-side action ever named.
 */
public final class WrenchClientState {

    private static BlockPos selection;

    private WrenchClientState() {
    }

    /** Replaces the highlight, or clears it with {@code null}. */
    public static void setSelection(@Nullable BlockPos pos) {
        selection = pos == null ? null : pos.immutable();
    }

    /** Forgets the highlight, e.g. when leaving the world. */
    public static void clear() {
        selection = null;
    }

    /** The highlighted block, or {@code null}. */
    @Nullable
    public static BlockPos selection() {
        return selection;
    }
}

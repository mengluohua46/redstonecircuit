package com.jiangyuefengyu.redstonecircuit.client;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

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

    /** The server's answer to "does this pick still point at a component?". */
    private static boolean live;

    /**
     * Which structure the pick is inside, and which of its blocks - {@code null} for a pick made in the world.
     *
     * <p>A world position is a whole block, while a machine draws its blocks between the grid lines, turned and scaled,
     * so a box placed on the nearest world coordinate hops while the machine slides. With the structure and the local
     * block named, the box can be placed by the <b>same frame the machine is drawn with</b> - see
     * {@code HostRenderPass#drawCarriedHighlight}.
     */
    private static CarrierId carriedCarrier;
    private static BlockPos carriedLocal;

    private WrenchClientState() {
    }

    /** Replaces the highlight, or clears it with {@code null}. */
    public static void setSelection(@Nullable BlockPos pos) {
        selection = pos == null ? null : pos.immutable();
    }

    /**
     * The same, with the server's answer to "does this pick still point at a component?".
     *
     * <p>The client cannot work this out for itself: a stale pick and a live carried host sit at the same coordinate, and
     * only the server holds both facts (see {@code WrenchSelection#holdsHost}). The box is drawn on this flag, so a pick
     * whose block is gone - or whose machine has moved - stops being drawn instead of hanging in the air.
     */
    public static void setSelection(@Nullable BlockPos pos, boolean liveHost) {
        setSelection(pos, liveHost, null, null);
    }

    /**
     * The whole of what the server said: where the pick is, whether it is still a host, and - for a pick inside a
     * machine - the structure and the local block it names.
     *
     * <p>One method rather than two setters, so a payload cannot update the position and leave a stale structure behind
     * it: a world pick arrives with both {@code null} and clears whatever was there, which is exactly right, because the
     * structure a previous pick named is not the one this one is about.
     */
    public static void setSelection(@Nullable BlockPos pos, boolean liveHost,
                                    @Nullable CarrierId carried, @Nullable BlockPos carriedLocal) {
        setSelection(pos);
        live = liveHost && pos != null;
        carriedCarrier = live ? carried : null;
        WrenchClientState.carriedLocal = carriedCarrier == null || carriedLocal == null
                ? null
                : carriedLocal.immutable();
    }

    /** True while the server says the pick still points at a block that holds a component. */
    public static boolean isLiveHost() {
        return live;
    }

    /** The structure a carried pick is inside, or {@code null} for a world pick (or no pick). */
    @Nullable
    public static CarrierId carriedCarrier() {
        return carriedCarrier;
    }

    /** That block's coordinate inside the structure, or {@code null}. */
    @Nullable
    public static BlockPos carriedLocal() {
        return carriedLocal;
    }

    /**
     * No host-removal handler lives here, and this is why: every tool click on a carried host sends a removal for the
     * position the machine is DRAWN at, which is the position a carried pick names, so such a handler would drop the box
     * for a live pick. The stale answer has to be re-sent by the <b>server</b>; see {@code WrenchSelection}.
     */

    /** Forgets the highlight, e.g. when leaving the world. */
    public static void clear() {
        selection = null;
        live = false;
        carriedCarrier = null;
        carriedLocal = null;
    }

    /** The highlighted block, or {@code null}. */
    @Nullable
    public static BlockPos selection() {
        return selection;
    }
}

package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * The world side of the two host transfers, as decisions that can be tested without a game.
 *
 * <p>A landing announces a world host only where the server's store really holds one, and a pick-up
 * suppresses one only where it really holds nothing, because a claim <em>moves</em> the data rather than
 * copying it.
 *
 * <p>{@link #broadcastsFor} gives the sequence a landing puts on the wire, and the order is contract: the
 * world host at the landing position first, so the client knows where the component is before it is told the
 * carrier is gone; then the removal of the old position when the structure drifted; then the carrier
 * message, whose absence of hosts is the "no longer riding" half.
 */
public final class WorldHostTransfer {

    /** One message a landing has to send. */
    public enum Broadcast {
        /** The landing position is a world host again: its contents, to everyone in the dimension. */
        RESTORE_WORLD_HOST,
        /** A position the component is no longer at: it must stop being drawn as a world host. */
        DROP_OLD_WORLD_HOST,
        /** The carrier message, with the released host absent from its list. */
        CARRIER
    }

    private WorldHostTransfer() {
    }

    /**
     * Whether a landing at {@code worldPos} has a world host to announce: true exactly when the server's own
     * store holds something there.
     */
    public static boolean restoresHost(@Nullable CarriedHostIndex.SlotWorld world,
                                       @Nullable BlockPos worldPos) {
        return world != null && worldPos != null && world.get(worldPos) != null;
    }

    /**
     * Whether a pick-up at {@code worldPos} has a world host to suppress: true exactly when the server's own
     * store holds nothing there. A component re-stored between the claim and the announcement is left alone.
     */
    public static boolean suppressesWorldHost(@Nullable CarriedHostIndex.SlotWorld world,
                                              @Nullable BlockPos worldPos) {
        return world != null && worldPos != null && world.get(worldPos) == null;
    }

    /** The messages a landing has to send, in order; {@code landing} is null when the release landed nowhere. */
    public static List<Broadcast> broadcastsFor(@Nullable BlockPos landing, @Nullable BlockPos origin,
                                                @Nullable CarriedHostIndex.SlotWorld world) {
        List<Broadcast> out = new ArrayList<>(3);
        boolean lands = restoresHost(world, landing);
        if (lands) {
            out.add(Broadcast.RESTORE_WORLD_HOST);
        }
        if (origin != null && !origin.equals(landing)) {
            // A structure that drifted leaves the origin entry behind; it has to go or the component is drawn twice.
            out.add(Broadcast.DROP_OLD_WORLD_HOST);
        }
        out.add(Broadcast.CARRIER);
        return List.copyOf(out);
    }

    /**
     * The same question for the Sable side, where a whole sub-level is released at once: identical rule, one
     * call per host. Answers how many of {@code positions} the store really holds a component at.
     */
    public static int restorableCount(@Nullable CarriedHostIndex.SlotWorld world,
                                      List<BlockPos> positions) {
        if (world == null || positions == null || positions.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (BlockPos pos : positions) {
            if (restoresHost(world, pos)) {
                total++;
            }
        }
        return total;
    }
}

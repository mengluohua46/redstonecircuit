package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The world side of the two host transfers, as a decision that can be tested without a game.</b>
 *
 * <h2>What it decides</h2>
 * Moving a component into a structure and back out of it are two transfers, and each one changes
 * <em>two</em> client-side facts about one world position:
 *
 * <table>
 *   <tr><th></th><th>is it a world host here?</th><th>does it ride that carrier?</th></tr>
 *   <tr><td><b>claim</b> (picked up)</td><td>no - and the client has to be told</td>
 *       <td>yes</td></tr>
 *   <tr><td><b>release</b> (put down)</td><td>yes - and the client has to be told</td>
 *       <td>no</td></tr>
 * </table>
 *
 * <p>The carrier half has always been sent ({@code HostSync.broadcastCarrier}), and the world half was
 * sent only at <em>pick-up</em> time, as an incidental effect of placing the component. The two halves
 * of a <em>landing</em> were therefore missing together, and the shape of the resulting defect is the
 * one the client cannot recover from on its own: the server's store has the component back at its
 * world position (a {@code /rc dump} and a broken block both find it) while the client's copy went out
 * with the carrier and nothing ever names that position again. The host is drawn by neither path -
 * invisible until some unrelated event touches it.
 *
 * <h2>Why this is a separate class</h2>
 * Both decisions are one predicate each over the server's own store - "is the component there, or
 * not?" - and that predicate is the whole of the correctness argument:
 *
 * <ul>
 *   <li>a landing announces a world host <b>only where the store really holds one</b>; a release that
 *       ended {@code HELD} wrote nothing, and announcing a world host there would be a lie;</li>
 *   <li>a pick-up suppresses a world host <b>only where the store really holds nothing</b>; a position
 *       somebody re-stored in the same tick is a world host and must not be taken off the world
 *       path.</li>
 * </ul>
 *
 * <p>Being a pure function of the store makes both of them unit-testable, which matters because the
 * alternative - "watch the game and see whether the component comes back" - is exactly the test that
 * failed to catch this defect the first time.
 *
 * <h2>Ordering</h2>
 * {@link #broadcastsFor} returns the sequence a landing has to put on the wire, in order, and the
 * order is part of the contract rather than an implementation detail:
 *
 * <ol>
 *   <li><b>the world host at the landing position</b> - so the client knows where the component is
 *       <em>before</em> it is told the carrier is gone. A client that acts on the removal first falls
 *       back to the world path, which draws the component only over a real block - and the block a
 *       structure comes home to is not guaranteed to be there;</li>
 *   <li><b>the removal of the old position</b>, when the structure drifted - so a client one packet
 *       behind cannot draw the component in two places at once;</li>
 *   <li><b>the carrier message</b>, whose absence of hosts is the "no longer riding" half.</li>
 * </ol>
 *
 * <p>A landed host is drawn by the client even when no block exists at its landing position (the
 * client keeps the put-down record - see {@code LandedHosts}), so the sequence above is about which
 * path draws the component rather than about whether it is drawn at all. It is still the order that
 * leaves no frame in which neither path can draw it.
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
     * Whether a landing at {@code worldPos} has a world host to announce.
     *
     * <p>True exactly when the server's own store holds something at that position. The release has
     * already run by the time this is asked, so "the store holds something" and "the component came
     * home here" are the same statement - which is the point: the announcement is derived from the
     * world the server will go on answering for, never from what the caller intended.
     */
    public static boolean restoresHost(@Nullable CarriedHostIndex.SlotWorld world,
                                       @Nullable BlockPos worldPos) {
        return world != null && worldPos != null && world.get(worldPos) != null;
    }

    /**
     * Whether a pick-up at {@code worldPos} has a world host to suppress.
     *
     * <p>True exactly when the server's own store holds nothing there any more - which is what a claim
     * leaves behind, because a claim <em>moves</em> the data rather than copying it. A component
     * re-stored at the same position between the claim and the announcement is deliberately left
     * alone: it is a world host, and suppressing it would hide a component that is really there.
     */
    public static boolean suppressesWorldHost(@Nullable CarriedHostIndex.SlotWorld world,
                                              @Nullable BlockPos worldPos) {
        return world != null && worldPos != null && world.get(worldPos) == null;
    }

    /**
     * The messages a landing has to send, in order.
     *
     * @param landing where the host was put down; {@code null} when the release did not land anywhere
     * @param origin where the host was picked up; {@code null} when the caller does not know
     * @param world the server's store, after the release has run
     * @return the sequence, possibly empty when there is nothing to announce
     */
    public static List<Broadcast> broadcastsFor(@Nullable BlockPos landing, @Nullable BlockPos origin,
                                                @Nullable CarriedHostIndex.SlotWorld world) {
        List<Broadcast> out = new ArrayList<>(3);
        boolean lands = restoresHost(world, landing);
        if (lands) {
            out.add(Broadcast.RESTORE_WORLD_HOST);
        }
        if (origin != null && !origin.equals(landing)) {
            // The client was told about the origin when the component was placed there. A structure
            // that drifted leaves that entry behind, and it has to go - otherwise the component is
            // drawn at both the position it left and the one it came home to.
            out.add(Broadcast.DROP_OLD_WORLD_HOST);
        }
        out.add(Broadcast.CARRIER);
        return List.copyOf(out);
    }

    /**
     * The same question for the Sable side, where a whole sub-level is released at once.
     *
     * <p>Identical rule, one call per host: each host that is really back in the store gets an
     * announcement. Exists as a named method rather than as a loop over {@link #restoresHost} at the
     * call site because "the wholesale path uses the same rule as the single-block path" is a claim
     * worth being able to see in one place.
     *
     * @return how many of {@code positions} the store really holds a component at
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

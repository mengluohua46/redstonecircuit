package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

import org.jetbrains.annotations.Nullable;

/**
 * <b>"One gesture, at most one action" - for a click that can be delivered more than once.</b>
 *
 * <h2>Why one click can arrive twice</h2>
 * A right-click on a block dispatches once for the main hand and once for the off hand, in the same
 * tick and at the same position - the fact {@code InnerRedstoneInteraction} and
 * {@code CarrierInteraction} have always handled. On a <b>carried</b> host there is a second way to be
 * told about the same click: Create's client-side interception turns a click on a contraption into
 * {@code ContraptionInteractionPacket}, and whether the ordinary block click is <em>also</em> sent is
 * not something this build has confirmed in game. The two deliveries name the same host, so a guard
 * keyed on the delivery would let both act.
 *
 * <h2>What it is keyed on, and why that is the honest key</h2>
 * A guard keyed on the clicked position cannot work here: the two deliveries are expressed in
 * <b>different coordinate frames</b> - the packet carries the contraption's {@code localPos}, the block
 * click carries whatever level position the clip produced. What both deliveries do name identically is
 * the thing the action is about: <b>the carrier and the host's local coordinate</b>.
 *
 * <p>So this is keyed on {@code (tick, carrier, localPos, hand)}:
 *
 * <ul>
 *   <li>the same host, the same tick, the <b>same hand</b> - two deliveries of one gesture, and the
 *       second one is dropped, whatever frame it arrived in;</li>
 *   <li>the same host, the same tick, a <b>different hand</b> - the main/off-hand pair, and the second
 *       one is dropped too: which hand is dispatched first is not something to depend on, so the first
 *       hand to be processed owns the click;</li>
 *   <li>a <b>different</b> host in the same tick - acted on normally. A player cannot meaningfully
 *       click two blocks in one tick, but a packet that names another host is not this guard's
 *       business, and refusing it would be a silent no-op rather than a rule.</li>
 * </ul>
 *
 * <p>Deliberately <b>static in production</b> (see {@code CarrierInteraction}), because the event
 * listener and the Create behaviour are two deliveries of one click and a per-instance guard would let
 * both act. It is an instance here so the rule itself is a plain object that a unit test can drive
 * without a game - the same split {@code CarriedHostIndex} uses.
 *
 * <h2>The bounded state</h2>
 * One tick, one carrier, one coordinate and one hand. Nothing accumulates, nothing is keyed by a
 * player or a level, and the object holds no reference to anything a level owns - so a shutdown, an
 * unload or a removed sub-level can leave it holding a stale tick number and nothing else.
 */
public final class ClickOnce {

    /** No tick has been recorded yet; the game's own clock starts at 0. */
    private static final long NEVER = Long.MIN_VALUE;

    private long tick = NEVER;
    @Nullable
    private UUID carrier;
    private long localPos = 0L;
    @Nullable
    private InteractionHand hand;

    /**
     * Claims one gesture, or reports that this exact one has already been handled.
     *
     * <p>Called once per entry point, before anything is written. A {@code false} answer means
     * "somebody already did this click" and the caller must do nothing at all - not even look at the
     * held item, because the first delivery already decided what it meant.
     *
     * @param tick the level's game time
     * @param carrier the structure the click resolved to
     * @param localPos the host's coordinate inside that structure
     * @param hand the hand the delivery named
     * @return true when this delivery owns the gesture and may act on it
     */
    public boolean claim(long tick, @Nullable CarrierId carrier, @Nullable BlockPos localPos,
                         @Nullable InteractionHand hand) {
        if (carrier == null || localPos == null || hand == null) {
            // An incomplete gesture is not one this guard can recognise, and refusing it would turn a
            // missing field into a silent no-op. The caller's own null checks own that case.
            return true;
        }
        boolean sameGesture = this.tick == tick
                && carrier.uuid().equals(this.carrier)
                && this.localPos == localPos.asLong();
        if (sameGesture) {
            return false;
        }
        this.tick = tick;
        this.carrier = carrier.uuid();
        this.localPos = localPos.asLong();
        this.hand = hand;
        return true;
    }

    /** Which hand owned the last claimed gesture, for the log line; {@code null} before the first. */
    @Nullable
    public InteractionHand hand() {
        return hand;
    }

    /** Forgets everything; called when a player leaves, exactly as the world's own guard is reset. */
    public void clear() {
        tick = NEVER;
        carrier = null;
        localPos = 0L;
        hand = null;
    }
}

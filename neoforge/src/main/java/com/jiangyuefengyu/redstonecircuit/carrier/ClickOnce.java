package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;

import org.jetbrains.annotations.Nullable;

/**
 * "One gesture, at most one action" - for a click that can be delivered more than once.
 *
 * <p>A right-click on a block dispatches once for the main hand and once for the off hand, in the same tick and at the
 * same position. On a <b>carried</b> host there is a second way to be told about the same click: Create's client-side
 * interception turns a click on a contraption into {@code ContraptionInteractionPacket}, and whether the ordinary block
 * click is <em>also</em> sent is not something this build has confirmed in game. The two deliveries name the same host,
 * so a guard keyed on the delivery would let both act.
 *
 * <p>A guard keyed on the clicked position cannot work either, because the two deliveries are expressed in
 * <b>different coordinate frames</b>: the packet carries the contraption's {@code localPos}, the block click whatever
 * level position the clip produced. What both name identically is what the action is about - the carrier and the host's
 * local coordinate - so this is keyed on {@code (tick, carrier, localPos, hand)}. One gesture delivered twice, or the
 * main/off-hand pair, is dropped, and which hand is dispatched first is not something to depend on, so the first hand
 * processed owns the click; a different host in the same tick is acted on normally.
 *
 * <p>The state is bounded - one tick, one carrier, one coordinate, one hand - so a shutdown, an unload or a removed
 * sub-level can leave it holding a stale tick number and nothing else. It is static in production (see
 * {@code CarrierInteraction}) because the event listener and the Create behaviour are two deliveries of one click, and
 * an instance here so the rule is a plain object a unit test can drive without a game.
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
     * <p>Called once per entry point, before anything is written. A {@code false} answer means "somebody already did
     * this click", and the caller must do nothing at all - not even look at the held item, because the first delivery
     * already decided what it meant. {@code tick} is the level's game time and {@code localPos} the host's coordinate
     * inside the structure.
     */
    public boolean claim(long tick, @Nullable CarrierId carrier, @Nullable BlockPos localPos,
                         @Nullable InteractionHand hand) {
        if (carrier == null || localPos == null || hand == null) {
            // An incomplete gesture is not one this guard can recognise; the caller's own null checks own that case.
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

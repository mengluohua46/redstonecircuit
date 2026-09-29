package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlockRemoval;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierEntryTrace;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * <b>The second break path: a block a structure is carrying has been broken, so drop the component.</b>
 *
 * <h2>Why this is a separate listener and not a branch in the existing one</h2>
 * {@code InnerRedstoneInteraction#onBlockBreak} is the world's break path, and it must stay exactly
 * what it was: it drops whatever the <em>level's own store</em> holds at the broken position. Adding a
 * "and also ask the carrier layer" step to it would put carrier logic on the world path - the one thing
 * P1.19's red lines forbid - and it would make the world path's behaviour depend on whether a structure
 * happens to be nearby.
 *
 * <p>This listener is therefore its own entry point, registered beside the world one, and it is
 * <b>inert on the world path</b>: {@link CarrierStore#dropDestroyedHost} returns immediately unless the
 * broken position is a carried host whose structure's own view says the block is gone. For an ordinary
 * break - a block in the world holding a component, or a block holding nothing at all - it does one
 * store lookup and one index isEmpty check and stops.
 *
 * <p>Both listeners fire for one break. They cannot both drop: the carried path refuses any position
 * the world store still holds (see {@code CarriedBlockRemoval#ownsDestruction}), and the world path
 * finds nothing at a position whose component is riding a structure (the claim <em>moves</em> the
 * entry out of the store rather than copying it).
 *
 * <h2>Why a break event at all</h2>
 * A carried block is removed by whoever owns it: the world's break handling for a Sable sub-level (its
 * blocks live in the level at their plot coordinates), and Create's own removal for a contraption. A
 * <b>break</b> is the case where a player destroyed the block and expects its contents, which is what
 * this answers. Nothing here fires for a release, a landing or a structure coming apart - those write
 * the component back through their own paths and must not also produce an item.
 *
 * <h2>P1.23: the trace line, and the one question an absent line answers</h2>
 * The author reported that breaking a physicalised block after the structure had moved still dropped
 * nothing. Every explanation for that - the block's removal never producing a break event at all, the
 * event arriving at a coordinate nothing resolves, the record being resolved and the drop declining it -
 * looks identical in a log that has no line for the break path. So this listener now writes
 * <b>exactly one</b> {@code carrier entry trace: entry=BREAK_EVENT …} line per break event it sees
 * ({@code debugLog}-gated and throttled by {@code CarrierEntryTrace}), which is also the positive proof
 * that a {@code BlockEvent.BreakEvent} <em>did</em> arrive at that coordinate. The complementary fact -
 * "the block left the structure and no break event was ever seen for it" - is written by the
 * self-healing sweep, which is the only code that can observe it, because a line that is never written
 * cannot be logged.
 *
 * <p>The trace reads the same two things the drop does ({@code destructionAt} / {@code hitAt}) and
 * changes neither, and the extra reads it makes for a refusal (the structure's own view, and whether the
 * world store holds the position) are read-only queries. It is deliberately placed <b>before</b> the
 * drop, so a break is reported even if the drop itself is the thing that throws.
 */
public final class CarrierBlockDrops {

    /** Warned once, so a broken position cannot fill the log a line per click. */
    private static boolean warned;

    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = event.getPos();
        // DIAGNOSTICS ONLY, AND BEFORE THE DROP: the line is the evidence that this listener was called
        // at all, so it must exist even for a break that then fails.
        trace(level, event, pos);
        try {
            CarrierStore.dropDestroyedHost(level, pos);
        } catch (RuntimeException | LinkageError failed) {
            // The world's break path is untouched by this listener; whatever went wrong there must not
            // take the break down, and one warn line is enough to report it.
            if (!warned) {
                warned = true;
                RCConfig.LOGGER.warn("[redstonecircuit] carrying-block drop check failed at {} "
                        + "(reported once): {}", pos.toShortString(), failed.toString());
            }
        }
    }

    /**
     * The one break-path trace line: what arrived, and what the carrier layer made of it.
     *
     * <p>Three outcomes, and the third one is the one that pays for this method:
     *
     * <ul>
     *   <li><b>{@code MATCH}</b> - the carrier layer owns this break and the drop below will move the
     *       component. The line carries the carrier, the host's local coordinate, the world position the
     *       pose projects it to and the position the item will be dropped at.</li>
     *   <li><b>{@code REFUSED}</b> - a live carried host <em>is</em> at this coordinate and the drop path
     *       declined it. The line names which of the three guards did so, read from the same two sources
     *       the decision reads: the structure's own block view, and whether the world's own store holds a
     *       component at the projected position.</li>
     *   <li><b>{@code MISS}</b> - nothing here is a carried host this mod knows about. This is still the
     *       positive receipt that a break event happened at this coordinate, which is exactly what an
     *       absent line cannot tell anybody.</li>
     * </ul>
     */
    private static void trace(ServerLevel level, BlockEvent.BreakEvent event, BlockPos pos) {
        if (!RCConfig.debugLog()) {
            return;
        }
        try {
            ServerPlayer player = event.getPlayer() instanceof ServerPlayer serverPlayer
                    ? serverPlayer : null;
            ItemStack stack = player == null ? null : player.getMainHandItem();
            // The two reads the drop itself makes, and no more than that where it can be helped: nothing
            // is carried in this dimension, nothing can resolve, and the resolver is not asked. Mining a
            // wall in a world with no structure in flight therefore costs two map lookups per block.
            CarriedBlockRemoval.Hit hit = CarrierEntryTrace.hasLiveCarriers(level)
                    ? CarrierStore.hitAt(level, pos) : null;
            CarriedBlockRemoval.Destroyed destroyed = CarrierStore.destroyedHostAt(level, pos);
            // THE LEDGER. Filed whenever a live carried host was identified, whether or not the drop
            // accepted it: the question the sweep later asks is "did a break event arrive for this
            // host", and a break that resolved to the host but was declined still arrived.
            if (hit != null) {
                CarrierEntryTrace.noteBreakEvent(hit.carrier(), hit.localPos(), pos);
            }
            if (destroyed != null) {
                CarrierEntryTrace.entry(level, "BREAK_EVENT", player, pos, null, null, stack,
                        CarrierEntryTrace.Outcome.MATCH, hit,
                        CarrierEntryTrace.destroyDetail(destroyed)
                                + " (the carrier layer owns this break; the drop below moves it)");
                return;
            }
            CarrierEntryTrace.entry(level, "BREAK_EVENT", player, pos, null, null, stack,
                    hit == null ? CarrierEntryTrace.Outcome.MISS
                            : CarrierEntryTrace.Outcome.REFUSED,
                    null, refusalDetail(level, pos, hit));
        } catch (RuntimeException | LinkageError failed) {
            // A trace must never take a break down; CarrierEntryTrace has its own once-only warning.
            RCConfig.LOGGER.warn("[redstonecircuit] carrier break trace failed at {} (not fatal): {}",
                    pos.toShortString(), failed.toString());
        }
    }

    /** Why the carrier layer did not take this break, naming the guard that stopped it. */
    private static String refusalDetail(ServerLevel level, BlockPos pos,
                                        CarriedBlockRemoval.Hit hit) {
        if (hit == null) {
            return "MISS: no live carried host answers for this position; a BreakEvent DID arrive here "
                    + "(this line is that receipt) " + CarrierEntryTrace.lookupAt(level,
                            "CarrierStore.hitAt(level, rawPos)", pos);
        }
        BlockPos now = CarrierStore.currentWorldPos(level, hit);
        boolean worldHolds;
        try {
            worldHolds = now != null && InnerRedstoneStore.get(level).slotAt(now) != null;
        } catch (RuntimeException | LinkageError unreadable) {
            worldHolds = false;
        }
        return "REFUSED: a live carried host IS at this position - carrier=" + hit.carrier().shortId()
                + " local=" + hit.localPos().toShortString()
                + " world=" + CarrierEntryTrace.posText(now)
                + " via=" + (hit.projected() ? "projection" : "structure-block")
                + " - but the drop path declined it: worldStoreHoldsThisPosition=" + worldHolds
                + " structureView=" + CarrierStore.structureViewState(hit.carrier(), hit.localPos())
                + " (the world's own store wins; and a hit found through the structure's own storage "
                + "additionally requires the view to say the block is gone)";
    }
}

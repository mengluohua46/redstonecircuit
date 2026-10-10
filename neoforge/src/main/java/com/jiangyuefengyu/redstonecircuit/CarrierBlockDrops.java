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
 * <p>The world's own break path stays what it was; this listener is <b>inert on the world path</b> -
 * {@link CarrierStore#dropDestroyedHost} returns immediately unless the broken position is a carried host
 * whose structure's own view says the block is gone. Both listeners fire for one break and cannot both
 * drop, because the carried path refuses any position the world store still holds.
 *
 * <p>A <b>break</b> is the case where a player destroyed the block and expects its contents.
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
        // Before the drop, so the line exists even for a break that then fails.
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
     * The one break-path trace line: {@code MATCH} (the carrier layer owns this break), {@code REFUSED}
     * (a live host is here and the drop path declined it, naming the guard) or {@code MISS} (nothing here
     * is a carried host, which is still the receipt that a break event arrived).
     */
    private static void trace(ServerLevel level, BlockEvent.BreakEvent event, BlockPos pos) {
        if (!RCConfig.debugLog()) {
            return;
        }
        try {
            ServerPlayer player = event.getPlayer() instanceof ServerPlayer serverPlayer
                    ? serverPlayer : null;
            ItemStack stack = player == null ? null : player.getMainHandItem();
            // The two reads the drop itself makes, and no more: mining a wall with no structure in flight
            // costs two map lookups per block.
            CarriedBlockRemoval.Hit hit = CarrierEntryTrace.hasLiveCarriers(level)
                    ? CarrierStore.hitAt(level, pos) : null;
            CarriedBlockRemoval.Destroyed destroyed = CarrierStore.destroyedHostAt(level, pos);
            // Filed whenever a live carried host was identified, accepted or not: a break that resolved to
            // the host but was declined still arrived, and that is the question asked later.
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

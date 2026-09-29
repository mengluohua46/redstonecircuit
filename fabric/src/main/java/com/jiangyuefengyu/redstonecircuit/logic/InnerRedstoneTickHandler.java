package com.jiangyuefengyu.redstonecircuit.logic;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

/**
 * Resolves queued inner-redstone power updates once per level tick.
 *
 * <p>Batching to the end of the tick means a burst of placements (or a chain reaction of block
 * changes) costs a single propagation pass instead of one per change.
 *
 * <h2>How the three hooks are wired on Fabric</h2>
 * All three used to be NeoForge bus events on one class. Fabric's counterparts are:
 *
 * <ul>
 *   <li>{@code LevelTickEvent.Post} → {@code ServerTickEvents.END_WORLD_TICK}, which hands over the
 *       {@link ServerLevel} directly instead of a {@code Level} to be filtered.</li>
 *   <li>{@code BlockEvent.NeighborNotifyEvent} → mixins into the three places NeoForge fires it
 *       ({@code ServerLevel#updateNeighborsAt}, {@code ServerLevel#updateNeighborsAtExceptFromFacing}
 *       and {@code DiodeBlock#updateNeighborsInFront}), because Fabric has no neighbour-notify event and
 *       the mod genuinely needs one: a lever placed against a host block changes no state of ours, so
 *       without this nothing would ever mark the network dirty. See
 *       {@link com.jiangyuefengyu.redstonecircuit.mixin.ServerLevelNeighborNotifyMixin}.</li>
 *   <li>{@code LevelEvent.Unload} → {@code ServerLifecycleEvents.SERVER_STOPPED}, since Fabric has no
 *       level-unload event. Waiting for the whole server to stop is coarser than dropping one level's
 *       queue, but it is the same guarantee for the case that matters - a stale position must not
 *       survive into the next run - and it costs nothing while the server is up.</li>
 * </ul>
 */
public final class InnerRedstoneTickHandler {

    /** Every direction's bit in the notified-sides mask handed to {@link #onNeighborNotify}. */
    public static final int ALL_SIDES = (1 << 6) - 1;

    private InnerRedstoneTickHandler() {
    }

    /** Registers the per-world tick. Called from the common entrypoint. */
    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(InnerRedstoneNetwork::tick);
    }

    /**
     * Registers the hook that drops the queues when the server goes.
     *
     * <p>Kept apart from {@link #register()} only so the entrypoint can be read as a list: this is the
     * one hook whose Fabric counterpart is a whole server stopping rather than a level unloading.
     */
    public static void registerServerStopped() {
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            for (ServerLevel level : server.getAllLevels()) {
                InnerRedstoneNetwork.clear(level);
            }
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info(
                        "[redstonecircuit] server stopped, cleared queued redstone updates");
            }
        });
    }

    /**
     * The bits of the notified-sides mask: one direction, and every side but one.
     *
     * <p>A mask rather than vanilla's {@code EnumSet} because this runs on the notification path of
     * every redstone change in the world, and an {@code EnumSet} there is an allocation per call.
     */
    public static int side(Direction direction) {
        return 1 << direction.ordinal();
    }

    /**
     * Every side except one, which is what {@code updateNeighborsAtExceptFromFacing} notifies.
     *
     * @param skipped the side vanilla was told to leave alone, or {@code null} when it skips none
     */
    public static int allSidesExcept(@Nullable Direction skipped) {
        return skipped == null ? ALL_SIDES : ALL_SIDES & ~side(skipped);
    }

    /**
     * Re-solves inner redstone when the world around a host block changes.
     *
     * <p>Without this, an inner component would never react to the outside world: placing a lever or
     * redstone torch against a host block changes no state of ours, so nothing would ever mark the
     * network dirty and the component would keep reporting its old power forever. That was a real
     * bug - levers and torches appeared to do nothing, and power stayed latched after the source was
     * removed.
     *
     * <p>Called from the mixins with the position that changed and the sides vanilla is notifying
     * around it, which is everything {@code NeighborNotifyEvent#getNotifiedSides} used to carry. The
     * caller also names itself, so {@code logs/latest.log} says which of the three notification paths
     * brought the news in.
     *
     * @param notifiedSides a bit mask of the directions being notified, as built by {@link #side} and
     *                      {@link #allSidesExcept}; the notified set is never empty in practice, and an
     *                      empty mask simply means "the changed block only"
     * @param source        the call site, for the log line only
     */
    public static void onNeighborNotify(ServerLevel level, BlockPos pos, int notifiedSides,
                                        String source) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store == null || store.isEmpty()) {
            return;
        }

        // The block whose output changed may itself hold a component. A diode (repeater, comparator)
        // and an observer notify the block they output INTO - `neighborChanged(output)` plus
        // `updateNeighborsAtExceptFromFacing(output, ...)` - so when a host block sits on the output
        // side, the notification arrives with the host as its position, not as a notified neighbour.
        // Only looking at the notified sides missed every one of them.
        if (markDirty(level, store, pos) && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] output change at host {} ({}) -> re-solving",
                    pos.toShortString(), source);
        }

        // The block at `pos` is the one that CHANGED. Its neighbours are the ones being notified, so a
        // host block is the *neighbour* here, not the source. Checking `pos` instead would miss every
        // case where the source disappeared: when a lever is broken, the position it used to occupy is
        // air by the time this runs.
        for (Direction direction : Direction.values()) {
            if ((notifiedSides & side(direction)) == 0) {
                continue;
            }
            BlockPos neighbour = pos.relative(direction);
            if (markDirty(level, store, neighbour) && RCConfig.debugLog()) {
                RCConfig.LOGGER.info(
                        "[redstonecircuit] world change at {} ({}) notified host {} -> re-solving",
                        pos.toShortString(), source, neighbour.toShortString());
            }
        }
    }

    /**
     * Queues a host for a re-solve, and says whether this call is the one that queued it.
     *
     * <p>The distinction exists for the log: one redstone change notifies the same host block from
     * several positions in a row (a wire strength change notifies its own position and all six
     * neighbours), so reporting every one of them would turn a lever flip into a wall of text. The
     * first notification is the one that scheduled the work, and it is the one worth printing.
     *
     * @return true when {@code candidate} holds a component and was not already queued
     */
    private static boolean markDirty(ServerLevel level, InnerRedstoneStore store, BlockPos candidate) {
        if (store.slotAt(candidate) == null) {
            return false;
        }
        boolean first = !InnerRedstoneNetwork.isQueued(level, candidate);
        InnerRedstoneNetwork.markDirty(level, candidate);
        return first;
    }
}

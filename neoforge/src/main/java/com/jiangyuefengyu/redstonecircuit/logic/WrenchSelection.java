package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedSelection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The block each player's wrench has picked as "A".
 *
 * <p>The second half of the gesture is a click on a different block, so the <b>server</b> remembers the
 * first: only it knows whether that block still holds a component when the second click arrives. A
 * selection is a half-finished gesture rather than data, so it lives in memory, goes with the player on
 * logout, and is checked against the level before use. The highlight is the server's answer too,
 * recomputed once per level tick and sent only when it changes.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID)
public final class WrenchSelection {

    /** Per player: the block picked, and the dimension it was picked in. */
    private static final Map<UUID, Selection> SELECTIONS = new HashMap<>();

    /**
     * What each player's client has last been told to show; absent means "nothing". Kept so the per-tick
     * recomputation sends a message only when the answer changes.
     */
    private static final Map<UUID, Shown> SHOWN = new HashMap<>();

    private record Selection(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /**
     * One player's highlight as the client has it: which block to frame, whether it is drawn, and - for a
     * pick inside a machine - which structure and which of its blocks it names.
     *
     * <p>{@code live} and the position are not independent: a pick whose host is gone is sent with
     * {@code live=false} and its last position, so the box can come back. {@code carried} and
     * {@code carriedLocal} travel together and are {@code null} for a world pick, letting the client draw
     * the box in the carrier's own frame rather than from a world position.
     */
    record Shown(@Nullable CarrierId carried, @Nullable BlockPos carriedLocal, BlockPos pos, boolean live) {

        Shown {
            pos = pos.immutable();
        }
    }

    private WrenchSelection() {
    }

    /** The block this player picked, or {@code null} if none, or if it was picked in another level. */
    @Nullable
    public static BlockPos get(ServerPlayer player) {
        Selection selection = SELECTIONS.get(player.getUUID());
        if (selection == null || !selection.dimension().equals(player.level().dimension())) {
            return null;
        }
        return selection.pos();
    }

    /** Picks a block, replacing any earlier pick. */
    public static void select(ServerPlayer player, BlockPos pos) {
        SELECTIONS.put(player.getUUID(), new Selection(player.level().dimension(), pos.immutable()));
        refresh(player);
    }

    /** Forgets the pick. */
    public static void clear(ServerPlayer player) {
        SELECTIONS.remove(player.getUUID());
        refresh(player);
    }

    /** Forgets the pick if it is the block given - used when that block is emptied or broken. */
    public static void clearIf(ServerLevel level, BlockPos pos) {
        for (ServerPlayer player : level.players()) {
            BlockPos selected = get(player);
            if (pos.equals(selected)) {
                clear(player);
            }
        }
    }

    /**
     * The highlight, recomputed once per level tick for every player in the level: on the tick rather than
     * on a block event, because the facts it is made of change for reasons no block event describes - a
     * structure picking the block up or carrying it across the world, a pose becoming unreadable, a
     * component taken out of a riding host.
     */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        List<ServerPlayer> players = level.players();
        for (int i = 0; i < players.size(); i++) {
            refresh(players.get(i));
        }
    }

    /**
     * Recomputes one player's highlight, and sends it only when it differs from what the client has. The
     * carried pick is asked first, and that is not a preference: the world's select half clears the
     * carrier's ledger but not the other way round ({@code InnerRedstoneInteraction#useWrench}), so a
     * carried pick is by construction the most recent gesture.
     */
    private static void refresh(ServerPlayer player) {
        BlockPos worldPick = get(player);
        CarriedSelection.Selection carried = CarriedSelection.get(player);
        boolean worldLive = worldPick != null && holdsHost(player, worldPick);
        BlockPos carriedPick = carried == null ? null : drawnPositionOf(player, carried);
        boolean carriedLive = carried != null && holdsCarried(player, carried);
        Shown shownCarried = carriedPicker(carried, carriedPick, carriedLive);
        Shown next = desired(worldPick, worldLive, shownCarried);
        if (!publish(player, next) || !RCConfig.debugLog()) {
            return;
        }
        // One line per CHANGE of the answer, never per tick, naming all four inputs it was made of.
        RCConfig.LOGGER.info("[redstonecircuit] selection box {} for {}: worldPick={} worldLive={}"
                        + " carried={} drawn={} carriedLive={}",
                next == null || !next.live() ? "hidden" : "shown", player.getName().getString(),
                describe(worldPick), worldLive, CarriedSelection.describe(carried),
                describe(carriedPick), carriedLive);
    }

    /**
     * The carried pick's own answer, or {@code null} when there is no carried pick. The three values that
     * have to agree - structure, local block, drawn position - are built in one place, and the identity is
     * the pick's, which is what {@code CarriedTool} compares on the second click.
     */
    @Nullable
    private static Shown carriedPicker(@Nullable CarriedSelection.Selection carried,
                                       @Nullable BlockPos drawn, boolean live) {
        return carried == null || drawn == null
                ? null
                : new Shown(carried.carrier(), carried.localPos(), drawn, live);
    }

    /**
     * The decision, as a pure function of the two ledgers' answers - the store lookups happen in
     * {@link #refresh} - so "which pick is the client shown, and is the box drawn" is a unit test.
     */
    static Shown desired(@Nullable BlockPos worldPick, boolean worldLive, @Nullable Shown carriedPick) {
        if (carriedPick != null) {
            return carriedPick;
        }
        return worldPick == null ? null : new Shown(null, null, worldPick, worldLive);
    }

    /** Sends the payload, if the client is not already showing this; returns whether anything was sent. */
    private static boolean publish(ServerPlayer player, @Nullable Shown next) {
        UUID id = player.getUUID();
        Shown current = SHOWN.get(id);
        if (current == null ? next == null : current.equals(next)) {
            return false;
        }
        if (next == null) {
            SHOWN.remove(id);
        } else {
            SHOWN.put(id, next);
        }
        PacketDistributor.sendToPlayer(player, next == null
                ? RCNetwork.WrenchSelectionPayload.none()
                : new RCNetwork.WrenchSelectionPayload(true, next.pos(), next.live(), next.carried(),
                        next.carriedLocal()));
        return true;
    }

    /**
     * <b>Whether this pick still points at a block that holds a component.</b> The client cannot answer this
     * for itself: a stale pick and a live carried host can sit at the same coordinate, and only the server
     * holds the pick, the world store and the carrier index.
     */
    private static boolean holdsHost(ServerPlayer player, BlockPos pos) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        if (com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore.get(level).slotAt(pos) != null) {
            return true;
        }
        if (carriedHostAt(level, pos)) {
            return true;
        }
        // And in the STRUCTURE's own frame: a click inside a machine names a plot cell, which neither
        // world-frame lookup above can answer. ProductFrame maps it to where the block is drawn.
        BlockPos drawn = com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame.carriedAt(level, pos).worldPosOf(pos);
        return !drawn.equals(pos) && carriedHostAt(level, drawn);
    }

    /**
     * Where a carried pick's block is drawn right now: its local coordinate projected through the carrier's
     * current pose, the same arithmetic the client draws the structure with. A pose that cannot be read is
     * not a refusal - the pick's recorded position is where the player was shown the block, and it beats
     * dropping the box for one tick.
     */
    private static BlockPos drawnPositionOf(ServerPlayer player, CarriedSelection.Selection pick) {
        CarrierPose pose = player.level() instanceof ServerLevel level
                ? CarrierStore.poseOf(level, pick.carrier())
                : null;
        return pose == null ? pick.worldPos() : CarrierProjection.project(pose, pick.localPos());
    }

    /** Whether a carried pick still points at a host that is riding this structure with something in it. */
    private static boolean holdsCarried(ServerPlayer player, CarriedSelection.Selection pick) {
        if (!(player.level() instanceof ServerLevel level)) {
            return false;
        }
        CarriedHostIndex index = CarrierStore.indexOf(level);
        CarriedHost host = index == null ? null : CarriedTool.hostAt(index, pick.carrier(), pick.localPos());
        // A landed record is not riding any more though it still answers from the index (CarriedHost#landed):
        // the block stands in the world, where the ordinary sync draws it, so the pick must not name it.
        return host != null && !host.landed() && host.slot() != null;
    }

    /** Whether the projection at this world position still resolves to a host holding a component. */
    private static boolean carriedHostAt(ServerLevel level, BlockPos worldPos) {
        com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlockRemoval.Hit hit =
                com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore.hitAt(level, worldPos);
        return hit != null && CarriedInsert
                .slotOf(com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore.indexOf(level), hit.carrier(),
                        hit.localPos()) != null;
    }

    /** {@code 2048, -60, 88} or {@code -} for a position that is not there. */
    private static String describe(@Nullable BlockPos pos) {
        return pos == null ? "-" : pos.toShortString();
    }

    /** Forgets a player's pick when they leave, so a UUID cannot pile up selections over time. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            SELECTIONS.remove(player.getUUID());
            // The client's copy goes with the connection, so the ledger that mirrors it has to go too.
            SHOWN.remove(player.getUUID());
        }
    }
}

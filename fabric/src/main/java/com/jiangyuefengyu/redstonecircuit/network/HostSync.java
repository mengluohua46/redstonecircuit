package com.jiangyuefengyu.redstonecircuit.network;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierTrace;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups;

import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/**
 * Server half of the host sync: keeps every client's copy of "which blocks hold redstone, and what is
 * inside them" current.
 *
 * <p>Two kinds of fact travel: what is standing in the world, and what is riding a moving structure. The
 * first needs traffic at three moments - a player arrives (login, dimension change, respawn) and therefore
 * has no data, and a component appears, changes or disappears. Nothing else is sent, because the client's
 * copy is a pure function of the store and there is no state to reconcile. The second is sent whenever a
 * structure or its contents change, and once to a player who arrives while something is in flight.
 *
 * <h2>The three arrival hooks, on Fabric</h2>
 * NeoForge had one {@code PlayerEvent} per case. Fabric splits them across two modules, and the
 * dimension change is the one worth reading twice:
 *
 * <ul>
 *   <li>login → {@code ServerPlayConnectionEvents.JOIN}. Not {@code INIT}: at JOIN the player is in the
 *       level and can already be sent play payloads.</li>
 *   <li>respawn → {@code ServerPlayerEvents.AFTER_RESPAWN}, which hands over the new player object -
 *       the one that is in the world, and therefore the one to send to.</li>
 *   <li>dimension change → {@code ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD}, whose
 *       {@code destination} is the level to snapshot. Passing the destination explicitly rather than
 *       reading {@code player.level()} also removes any doubt about which side of the move we are on.
 *       The client's cache is keyed by dimension, so the new level's snapshot simply replaces it.</li>
 * </ul>
 */
public final class HostSync {

    private HostSync() {
    }

    /** Registers the three arrival hooks. Called from the common entrypoint. */
    public static void register() {
        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> sendSnapshot(handler.getPlayer()));
        ServerPlayerEvents.AFTER_RESPAWN.register(
                (oldPlayer, newPlayer, alive) -> sendSnapshot(newPlayer));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                (player, origin, destination) -> sendSnapshot(player));
    }

    /** Sends the complete set for the player's current dimension, contents included. */
    public static void sendSnapshot(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        ServerPlayNetworking.send(player,
                new RCNetwork.HostSnapshotPayload(level.dimension(), snapshotOf(level)));
        // 同步火把的组跟着一起走: a client's group list is only ever a copy of the server's, so a player who
        // arrives with none of it must be told the whole thing - an empty list included, which is what clears
        // whatever the previous dimension's arrival left behind.
        RCNetwork.SyncGroupPayload groups = syncGroupSnapshotOf(level);
        rememberGroups(level.dimension(), groups);
        ServerPlayNetworking.send(player, groups);
        // A client that arrives while something is in flight has no way to learn about it otherwise:
        // the host sync above only carries what is standing in the world, and the poses are sent on
        // change, which for a parked machine is never.
        sendCarrierSnapshot(player);
    }

    /**
     * 已经发出去的那一份整份名单, 一个维度一份: 只用来回答"这一次是不是真的少了一个成员"。
     *
     * <p>记的是<b>发出去的那一份</b>而不是"现在的名单": 客户端手里那份缓存只可能是某一次发出去的名单, 于是拿它跟现在
     * 比, 差出来的就是客户端还不知道的那件事。
     */
    private static final Map<ResourceKey<Level>, List<RCNetwork.SyncGroupPayload.SyncGroup>> SENT_GROUPS
            = new HashMap<>();

    /**
     * 同步火把的组, 整份: 一个维度里每个频道有谁.
     *
     * <p>整份而不是增量, 因为组的成员是相对的 (两个相隔很远的火把属于一起), 而客户端看得见的只有脚下 - 所以"这一组还有
     * 没有别人"只有服务器答得上来, 而丢了一条增量就会让客户端永远多画一个成员。
     */
    public static RCNetwork.SyncGroupPayload syncGroupSnapshotOf(ServerLevel level) {
        // 两段来自同一次收集: "这一组还有没有别人"是关于整份名单的一句话, 分两次问就会分两次答 - 而一台被搬着的
        // 机器正好在两问之间放下它的火把时, 客户端会拿到一个"世界那一段没有它、搬运那一段也没有它"的组。
        SyncGroups.Snapshot snapshot = SyncGroups.snapshotOf(level);
        Set<Integer> channels = new LinkedHashSet<>(snapshot.standing().keySet());
        channels.addAll(snapshot.carried().keySet());
        List<RCNetwork.SyncGroupPayload.SyncGroup> groups = new ArrayList<>(channels.size());
        for (int channel : channels) {
            List<RCNetwork.SyncGroupPayload.CarriedMember> carried = new ArrayList<>();
            for (SyncGroups.CarriedSpot spot : snapshot.carried().getOrDefault(channel, List.of())) {
                carried.add(new RCNetwork.SyncGroupPayload.CarriedMember(spot.carrier(), spot.local()));
            }
            groups.add(new RCNetwork.SyncGroupPayload.SyncGroup(channel,
                    List.copyOf(snapshot.standing().getOrDefault(channel, List.of())),
                    List.copyOf(carried)));
        }
        return new RCNetwork.SyncGroupPayload(level.dimension(), List.copyOf(groups));
    }

    /** Tells every client in the level the whole group state, because a group changed. */
    public static void broadcastSyncGroups(ServerLevel level) {
        sendGroups(level, syncGroupSnapshotOf(level));
    }

    /**
     * 整份名单重发 - <b>但只在真的少了一个成员的时候</b>。
     *
     * <p>为什么需要这一条: 从一台<b>被搬着的</b>宿主里把同步火把取出来, 写的是搬运索引
     * ({@code CarriedInsert#apply} → {@code CarriedHostIndex#updateSlot}), 世界 store 一行都没动, 于是
     * {@code InnerRedstoneInteraction#forget} 根本不在这条路上; 它告诉客户端的只有"这一格空了"
     * ({@code CarrierInteraction#broadcastPlacementChange} 走的就是 {@link #broadcast(ServerLevel, BlockPos, Slot)})。
     * 而"这一组还有谁"是哪个槽位都装不下的一句话 - 客户端手里那份名单只能整份换掉, 否则那条眼镜连线留在原地,
     * 直到玩家重登。
     *
     * <p>为什么不是每次都发: 这一句挂在 {@link #broadcast(ServerLevel, List)} 的<b>删除那一半</b>上, 而删除是取出、
     * 活塞搬走、落地、指令共用的; 每次交互都重发一份名单就是给每次交互多发一个包。判据只问一件事: 上一次发出去的
     * 那份名单里, 有没有一个成员这一次<b>不在里面了</b>. 多出来的成员不算 (没人看见过它, 而加进去的那条路自己会发),
     * 名单整个没变也不算。
     *
     * @return 真的重发了没有
     */
    public static boolean broadcastSyncGroupsIfMemberLeft(ServerLevel level) {
        RCNetwork.SyncGroupPayload payload = syncGroupSnapshotOf(level);
        List<RCNetwork.SyncGroupPayload.SyncGroup> sent = SENT_GROUPS.get(level.dimension());
        if (sent == null) {
            // 这个维度一份名单都还没发出去过, 于是没有谁的缓存需要纠正; 但底要留下, 否则下一次比较无从谈起。
            rememberGroups(level.dimension(), payload);
            return false;
        }
        if (!lostMember(sent, payload.groups())) {
            return false;
        }
        sendGroups(level, payload);
        return true;
    }

    /**
     * 上一次发出去的名单里, 有没有一个成员这一次不在里面了 - <b>纯值</b>, 不读世界、不发包, 于是判据本身不必跑游戏
     * 就能钉住: "取出一个成员" 与 "只是多了一个成员" 在这里必须是两个答案。
     */
    static boolean lostMember(List<RCNetwork.SyncGroupPayload.SyncGroup> sent,
                              List<RCNetwork.SyncGroupPayload.SyncGroup> now) {
        Map<Integer, RCNetwork.SyncGroupPayload.SyncGroup> current = new HashMap<>();
        for (RCNetwork.SyncGroupPayload.SyncGroup group : now) {
            current.put(group.channel(), group);
        }
        for (RCNetwork.SyncGroupPayload.SyncGroup group : sent) {
            RCNetwork.SyncGroupPayload.SyncGroup after = current.get(group.channel());
            if (after == null) {
                // 整个频道没了: 频道里要是有人, 那就是人走了; 一个空频道消失说不了什么。
                if (!group.members().isEmpty() || !group.carried().isEmpty()) {
                    return true;
                }
                continue;
            }
            for (BlockPos pos : group.members()) {
                if (!after.members().contains(pos)) {
                    return true;
                }
            }
            for (RCNetwork.SyncGroupPayload.CarriedMember member : group.carried()) {
                if (!after.carried().contains(member)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The one place that puts the whole group state on the wire, so the ledger is advanced exactly once per send. */
    private static void sendGroups(ServerLevel level, RCNetwork.SyncGroupPayload payload) {
        rememberGroups(level.dimension(), payload);
        for (ServerPlayer player : level.players()) {
            ServerPlayNetworking.send(player, payload);
        }
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] sync torch groups in {}: {}",
                    level.dimension().location(), payload.groups().size());
        }
    }

    /** 记下"客户端接下来手里就是这一份", 于是下一次"是不是少了一个人"问的是同一件事。 */
    private static void rememberGroups(ResourceKey<Level> dimension, RCNetwork.SyncGroupPayload payload) {
        SENT_GROUPS.put(dimension, payload.groups());
    }


    /** Everything stored in one level, as the wire form. */
    public static List<HostEntry> snapshotOf(ServerLevel level) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (store.isEmpty()) {
            return List.of();
        }
        List<HostEntry> entries = new ArrayList<>();
        for (BlockPos pos : store.positions()) {
            InnerRedstoneNode node = store.get(pos);
            if (node != null && !node.isEmpty()) {
                entries.add(new HostEntry(pos.immutable(), node.slot().copy()));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * Tells clients that these positions changed: added, updated, or (with a {@code null} slot) emptied.
     *
     * <p>Delivered to everyone in the dimension by default. The alternative - only players near enough to
     * see it - is tempting, since this fires on every step a wire takes, but a client that missed an
     * update keeps drawing the old state until it happens to be re-sent, and nothing re-sends it.
     * {@code slotSyncRange} exists for servers that would rather have the bandwidth: set it to a positive
     * block radius and updates outside it are dropped for that player, at the cost of a stale drawing
     * until the next login or dimension change re-snapshots.
     */
    public static void broadcast(ServerLevel level, List<HostEntry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        double range = RCConfig.slotSyncRange();
        RCNetwork.HostSlotPayload payload = new RCNetwork.HostSlotPayload(
                level.dimension(), copyOf(entries));
        for (ServerPlayer player : level.players()) {
            if (range > 0 && !withinRange(player, entries, range)) {
                continue;
            }
            ServerPlayNetworking.send(player, payload);
        }
        if (RCConfig.debugLog() && RCConfig.traceSlotSync()) {
            RCConfig.LOGGER.info("[redstonecircuit] sync {} position(s): {}",
                    entries.size(), describe(entries));
        }
        // A DIAGNOSTIC, AND NOTHING ELSE - it changes no message, no record and no counter the rest of the
        // feature reads. See noteAddWhereNoHostStands.
        noteAddWhereNoHostStands(level, entries);
        // 这一批里可能有一个"这里空了", 而空掉的那一格也许正是某个频道的一个成员: "这一组还有谁"是哪个槽位都装不下
        // 的一句话, 于是只能整份重发 - 而且只在真的少了一个人时 (见 broadcastSyncGroupsIfMemberLeft)。这一半是
        // <b>所有</b>删除的入口: 世界里取出/挖掉、活塞搬走、被搬着的那一格取出元件、落地、指令, 全部经过这里。
        if (holdsRemoval(entries)) {
            broadcastSyncGroupsIfMemberLeft(level);
        }
    }


    /** 这一批里有没有一个位置被说成"什么都没有了"。 */
    private static boolean holdsRemoval(List<HostEntry> entries) {
        for (HostEntry entry : entries) {
            if (entry.slot() == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>Names every addition this message makes at a position nothing owns.</b>
     *
     * <p>Two refusals, and each rules out one of the two legitimate authors of "a component is announced here and
     * the world store holds nothing": a position a live structure is <b>projecting</b> a host onto is the carried
     * path's own cell (the block is inside the structure, so the world store has nothing there by construction),
     * and a position the store does hold a record for is the ordinary world sync. Neither is an anomaly, so
     * neither is counted - which is what makes the surviving line mean something.
     *
     * <p>Not a behaviour: the packet has already been sent by the time this runs, and this method only reads,
     * counts and writes a log line.
     *
     * <p><b>Bounded, because a moving machine produces this shape every tick.</b> The gate is the operator's
     * ({@code debugLog}); behind it sits a window of {@link #ADD_WITHOUT_HOST_WINDOW_TICKS} game ticks. The first
     * such message of a window writes one line carrying the window's count, and every message after it inside the
     * same window only adds to that count. The window is opened by the first message seen and rolls over on game
     * time rather than a wall clock, so it costs two static numbers and never a timer. Both are shared by all
     * dimensions on purpose: the budget this protects is the log's, not a level's.
     */
    private static void noteAddWhereNoHostStands(ServerLevel level, List<HostEntry> entries) {
        if (!RCConfig.debugLog()) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        BlockPos first = null;
        String type = null;
        int found = 0;
        for (HostEntry entry : entries) {
            // A removal for an unstored cell is the ordinary, correct message; only an ADDITION there is the shape
            // being hunted.
            if (entry.slot() == null || store.slotAt(entry.pos()) != null) {
                continue;
            }
            // A position a live carrier is projecting a host onto is drawn by the carried path and announced
            // here on purpose, so it is the ordinary case rather than this line's business. One projection-table
            // lookup, and only while the carrier layer is armed.
            if (CarrierStore.isProjectedAt(level, entry.pos())) {
                continue;
            }
            if (first == null) {
                first = entry.pos();
                type = entry.slot().type.name();
            }
            found++;
        }
        if (found == 0) {
            return;
        }
        long now = level.getGameTime();
        if (addWindowStart < 0L || now < addWindowStart
                || now - addWindowStart >= ADD_WITHOUT_HOST_WINDOW_TICKS) {
            // A fresh window: this message is the first one it will report.
            addWindowStart = now;
            addsThisWindow = 0;
        }
        addsThisWindow += found;
        if (addsThisWindow != found) {
            // Somewhere inside a window that has already spoken: counted, not printed.
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] world ADD with no owner at {} slot={} (the world store holds "
                        + "nothing there and no live structure projects a host onto it), {} this window",
                first.toShortString(), type, addsThisWindow);
    }

    /** How long one window of {@link #noteAddWhereNoHostStands} lasts, in game ticks. */
    private static final long ADD_WITHOUT_HOST_WINDOW_TICKS = 20L;

    /** When the current window opened, or {@code -1} before the first; game time, never a wall clock. */
    private static long addWindowStart = -1L;

    /** How many such additions the current window has seen. */
    private static int addsThisWindow;

    /** Tells clients about one position: contents, or {@code null} to say it is now empty. */
    public static void broadcast(ServerLevel level, BlockPos pos, @Nullable Slot slot) {
        broadcast(level, List.of(new HostEntry(pos.immutable(), slot == null ? null : slot.copy())));
    }

    /**
     * Tells clients that a position no longer holds anything: a host that has <em>moved</em> rather than one
     * that was emptied. Not ordered here - the caller must announce the new position first.
     */
    public static void broadcastRemoval(ServerLevel level, BlockPos pos) {
        broadcast(level, pos, null);
    }

    /**
     * Makes the world-side entry at a position a carried host is only <em>drawn</em> at say "nothing here",
     * unless the world keeps a host of its own there: a carried host lives at the structure's coordinates.
     */
    public static void publishWorldEntry(ServerLevel level, BlockPos worldPos) {
        publishWorldEntries(level, List.of(worldPos));
    }

    /**
     * <b>The same sentence for several positions at once, in one message.</b>
     *
     * <p>One place owes it for a whole batch, because one event creates the batch: a structure that drifts or
     * turns leaves every host it carries behind at the position it was last <em>projected</em> onto, and each of
     * those was announced here when its component was put in ({@code CarrierInteraction#broadcastPlacementChange}).
     * They are one fact about one move ({@code CarrierStore#withdrawLeftWorldEntries}), found by a walk that runs
     * per tick, so the batch is the shape the message belongs in - a packet per host per move is the alternative.
     *
     * <p>The guard is the one the single-position form has always had, asked here instead so the rule keeps its
     * one home: a position the world store really holds a component at is the ordinary world sync's, and a
     * removal sent here would erase a component the player really has. Nothing is dropped - this is a message,
     * never a destruction.
     */
    public static void publishWorldEntries(ServerLevel level, List<BlockPos> worldPositions) {
        if (worldPositions.isEmpty()) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        List<HostEntry> entries = new ArrayList<>(worldPositions.size());
        for (BlockPos worldPos : worldPositions) {
            if (store.slotAt(worldPos) != null) {
                continue;
            }
            entries.add(new HostEntry(worldPos.immutable(), null));
        }
        broadcast(level, entries);
    }

    // ------------------------------------------------------------- carriers --

    /**
     * Tells clients everything they need to know about one moving structure; a {@code null} pose means it is
     * gone. Pose and hosts travel together because one without the other draws a component that is not there.
     */
    public static void broadcastCarrier(ServerLevel level, CarrierId carrier,
                                        @Nullable CarriedHostIndex index,
                                        @Nullable CarrierPose pose) {
        broadcastCarrier(level, carrier, index, pose, List.of());
    }

    /**
     * The same, with the hosts the server has <b>discarded</b> named explicitly - the one thing absence from
     * the host list cannot say, since a host leaves it either by being put down or by being destroyed.
     */
    public static void broadcastCarrier(ServerLevel level, CarrierId carrier,
                                        @Nullable CarriedHostIndex index,
                                        @Nullable CarrierPose pose, List<BlockPos> removed) {
        CarriedHostIndex resolved = index == null ? CarrierStore.indexOf(level) : index;
        sendToDimension(level, carrierPayload(level, carrier, resolved, pose, removed));
        // Counted here rather than at the call sites, so every route that puts a carrier on the wire is
        // counted exactly once.
        int liveHosts = resolved == null ? 0 : resolved.liveHostsOf(carrier).size();
        CarrierTrace.noteCarrierSent(level.dimension(), liveHosts);
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] sent carrier {} pose={} carriedHosts={} removed={} {}",
                    carrier.shortId(), pose == null ? "none" : pose.describe(), liveHosts,
                    removed == null ? 0 : removed.size(),
                    resolved == null ? "hosts=?" : resolved.summary(carrier));
        }
    }

    /** Tells clients only where a structure is; used while it moves. */
    public static void broadcastPose(ServerLevel level, CarrierId carrier,
                                     @Nullable CarrierPose pose) {
        broadcastPose(level, carrier, pose, true);
    }

    /**
     * The same, with the three-stage counter made optional: a heartbeat pose - re-sent because the structure
     * is <em>still there</em> - must not be counted as a pose sent while it moved.
     */
    public static void broadcastPose(ServerLevel level, CarrierId carrier,
                                     @Nullable CarrierPose pose, boolean countAsChange) {
        sendToDimension(level, carrierPayload(level, carrier, CarrierStore.indexOf(level), pose));
        if (countAsChange) {
            CarrierTrace.notePoseSent(level.dimension());
        }
    }

    /** Everything in flight in one dimension, for a client that has just arrived. */
    public static RCNetwork.CarrierSnapshotPayload carrierSnapshotOf(ServerLevel level) {
        List<RCNetwork.CarrierPayload> carriers = new ArrayList<>();
        CarriedHostIndex index = CarrierStore.indexOf(level);
        if (index != null) {
            for (CarrierId carrier : index.carriers()) {
                carriers.add(carrierPayload(level, carrier, index,
                        CarrierStore.poseOf(level, carrier)));
            }
        }
        return new RCNetwork.CarrierSnapshotPayload(level.dimension(), List.copyOf(carriers));
    }

    /** Sends the complete carrier state to one player. */
    public static void sendCarrierSnapshot(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }
        if (!CarrierStore.isActive()) {
            return;
        }
        RCNetwork.CarrierSnapshotPayload payload = carrierSnapshotOf(level);
        ServerPlayNetworking.send(player, payload);
        // The ledger that decides whether a removal is owed is advanced only after the packet went out.
        List<CarrierId> named = new ArrayList<>(payload.carriers().size());
        for (RCNetwork.CarrierPayload carrier : payload.carriers()) {
            named.add(carrier.carrier());
        }
        CarrierStore.noteSnapshotSent(level, named);
        CarrierTrace.noteSnapshotSent(level.dimension(), payload.carriers().size());
    }

    /**
     * Builds the message for one carrier: its pose, and every host that is still riding it. Hosts that have
     * been put down are left out - they are back in the world, which the ordinary host sync covers.
     */
    private static RCNetwork.CarrierPayload carrierPayload(ServerLevel level, CarrierId carrier,
                                                           @Nullable CarriedHostIndex index,
                                                           @Nullable CarrierPose pose) {
        return carrierPayload(level, carrier, index, pose, List.of());
    }

    /**
     * The same message, with the discarded host coordinates attached. The list is copied and immutable so a
     * payload handed to the network cannot change under it.
     */
    private static RCNetwork.CarrierPayload carrierPayload(ServerLevel level, CarrierId carrier,
                                                           @Nullable CarriedHostIndex index,
                                                           @Nullable CarrierPose pose,
                                                           List<BlockPos> removed) {
        List<CarrierIndex.CarriedView> views = new ArrayList<>();
        if (index != null) {
            for (CarriedHost host : index.liveHostsOf(carrier)) {
                // An empty host is a state, not a crash: its slot is null, so the view is left out and the
                // client is told the host carries no component.
                Slot slot = host.slot();
                if (slot == null) {
                    continue;
                }
                views.add(new CarrierIndex.CarriedView(carrier, host.localPos().immutable(),
                        host.origin().immutable(), host.at().immutable(), slot.copy()));
            }
        }
        return new RCNetwork.CarrierPayload(level.dimension(), carrier, pose, List.copyOf(views),
                removed == null ? List.of() : List.copyOf(removed));
    }

    /** How many carriers and hosts a client would be told about; used by {@code /rc physics}. */
    public static Map<CarrierId, CarrierPose> carrierPosesOf(ServerLevel level) {
        Map<CarrierId, CarrierPose> poses = new HashMap<>();
        for (CarrierId carrier : CarrierStore.carriersOf(level)) {
            CarrierPose pose = CarrierStore.poseOf(level, carrier);
            if (pose != null) {
                poses.put(carrier, pose);
            }
        }
        return poses;
    }

    /** Every carried host of a level, in the shape the client would be sent. */
    public static List<CarrierIndex.CarriedView> carriedHostsOf(ServerLevel level) {
        List<CarrierIndex.CarriedView> views = new ArrayList<>();
        CarriedHostIndex index = CarrierStore.indexOf(level);
        if (index == null) {
            return views;
        }
        for (CarrierId carrier : index.carriers()) {
            for (CarriedHost host : index.liveHostsOf(carrier)) {
                views.add(new CarrierIndex.CarriedView(carrier, host.localPos(), host.origin(),
                        host.at(), host.slot()));
            }
        }
        return views;
    }

    /**
     * One message to every player in a dimension.
     *
     * <p>Written out rather than reaching for a broadcast helper, because the payload has to be the
     * <em>same object</em> for every player: it is built once and sent N times, which is what keeps a
     * machine with twenty carriers from building twenty copies of every message.
     */
    private static void sendToDimension(ServerLevel level, RCNetwork.CarrierPayload payload) {
        for (ServerPlayer player : level.players()) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    private static boolean withinRange(ServerPlayer player, List<HostEntry> entries, double range) {
        BlockPos playerPos = player.blockPosition();
        for (HostEntry entry : entries) {
            if (playerPos.closerThan(entry.pos(), range)) {
                return true;
            }
        }
        return false;
    }

    private static List<HostEntry> copyOf(List<HostEntry> entries) {
        List<HostEntry> copy = new ArrayList<>(entries.size());
        for (HostEntry entry : entries) {
            // Copied, not referenced: the solver keeps mutating its slots after the packet is handed
            // over, and a payload that changed under the network thread would send nonsense.
            copy.add(new HostEntry(entry.pos().immutable(),
                    entry.slot() == null ? null : entry.slot().copy()));
        }
        return copy;
    }

    private static String describe(List<HostEntry> entries) {
        if (entries.size() == 1) {
            HostEntry only = entries.get(0);
            return only.pos().toShortString() + " -> "
                    + (only.slot() == null ? "removed" : only.slot().describe());
        }
        return entries.size() + " entries";
    }
}
